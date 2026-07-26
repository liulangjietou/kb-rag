package io.kbrag.app.kb;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.kbrag.app.index.EngineChunkCleaner;
import io.kbrag.app.index.IndexAliasManager;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.entity.DocumentVersion;
import io.kbrag.domain.entity.KnowledgeBase;
import io.kbrag.domain.mapper.ChunkMapper;
import io.kbrag.domain.mapper.DocumentMapper;
import io.kbrag.domain.mapper.DocumentVersionMapper;
import io.kbrag.domain.mapper.KnowledgeBaseMapper;
import io.kbrag.domain.enums.SplitStrategy;
import io.kbrag.domain.model.KbIndexConfig;
import io.kbrag.domain.model.KbRetrievalConfig;
import io.kbrag.domain.model.ParentChildParams;
import io.kbrag.domain.port.ChatProvider;
import io.kbrag.domain.port.VisionProvider;
import io.kbrag.domain.service.BizIdGenerator;
import io.kbrag.domain.service.FixedLengthTextSplitter;
import io.kbrag.domain.service.VersionFingerprintFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Knowledge base lifecycle and configuration.
 *
 * <p>Creating a knowledge base also materialises its physical indices and aliases, so an upload never
 * has to wait for a lazy first time index creation and the console can show the index state panel
 * immediately.
 *
 * <p>Configuration changes are detected rather than enforced. Changing the split parameters does not
 * touch a single existing chunk: it recomputes the fingerprint and marks the documents whose active
 * version was built with a different one, leaving the operator to decide when to pay for the rebuild.
 * That separation is what makes tuning safe on a live knowledge base.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private static final int STALE = 1;
    private static final int FRESH = 0;
    /** Identifiers per {@code IN} predicate, kept well under what a default MySQL will accept. */
    private static final int CHUNK_DELETE_BATCH = 500;

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final DocumentMapper documentMapper;
    private final DocumentVersionMapper documentVersionMapper;
    private final ChunkMapper chunkMapper;
    private final BizIdGenerator bizIdGenerator;
    private final IndexAliasManager indexAliasManager;
    private final EngineChunkCleaner engineChunkCleaner;
    private final VersionFingerprintFactory fingerprintFactory;
    private final VisionProvider visionProvider;
    private final ChatProvider chatProvider;
    private final KbProperties properties;

    /**
     * Creates a knowledge base and its indices.
     *
     * @param name        display name
     * @param description free text description
     * @return created aggregate
     */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeBase create(String name, String description) {
        if (knowledgeBaseMapper.exists(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getName, name))) {
            throw BizException.invalidParam("knowledge base name already exists");
        }
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setKbId(bizIdGenerator.knowledgeBaseId());
        knowledgeBase.setName(name);
        knowledgeBase.setDescription(description);
        KbIndexConfig config = defaultIndexConfig();
        knowledgeBase.setIndexConfig(JsonUtil.toJson(config));
        knowledgeBase.setCurrentConfigFingerprint(configFingerprint(config));
        knowledgeBaseMapper.insert(knowledgeBase);
        indexAliasManager.ensureIndexes(knowledgeBase.getKbId());
        log.info("knowledge base created, kbId={}, name={}", knowledgeBase.getKbId(), name);
        return knowledgeBase;
    }

    /**
     * Lists every knowledge base, newest first.
     *
     * @return knowledge bases
     */
    public List<KnowledgeBase> list() {
        return knowledgeBaseMapper.selectList(new LambdaQueryWrapper<KnowledgeBase>()
                .orderByDesc(KnowledgeBase::getId));
    }

    /**
     * Loads a knowledge base or fails.
     *
     * @param kbId business id
     * @return knowledge base
     */
    public KnowledgeBase require(String kbId) {
        KnowledgeBase knowledgeBase = knowledgeBaseMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getKbId, kbId)
                .last("limit 1"));
        if (knowledgeBase == null) {
            throw BizException.notFound("knowledge base not found");
        }
        return knowledgeBase;
    }

    /**
     * Reads the index configuration of a knowledge base, substituting the deployment defaults when the
     * column is empty.
     *
     * @param knowledgeBase knowledge base aggregate
     * @return index configuration, never {@code null}
     */
    public KbIndexConfig indexConfigOf(KnowledgeBase knowledgeBase) {
        KbIndexConfig config = JsonUtil.parse(knowledgeBase.getIndexConfig(), KbIndexConfig.class);
        return config == null ? defaultIndexConfig() : config;
    }

    /**
     * Reads the index configuration by knowledge base id.
     *
     * @param kbId knowledge base business id
     * @return index configuration, never {@code null}
     */
    public KbIndexConfig indexConfigOf(String kbId) {
        return indexConfigOf(require(kbId));
    }

    /**
     * Replaces the index configuration and marks the documents it invalidates.
     *
     * <p>The two operations belong to the same transaction: a fingerprint no document was compared
     * against would leave the console showing a clean state over a stale index.
     *
     * @param kbId            knowledge base business id
     * @param config          new index configuration
     * @param retrievalConfig new retrieval defaults, {@code null} keeps the stored ones
     * @return number of documents currently marked as stale
     */
    @Transactional(rollbackFor = Exception.class)
    public int updateIndexConfig(String kbId, KbIndexConfig config, KbRetrievalConfig retrievalConfig) {
        requireSplitStrategyUsable(config);
        KnowledgeBase knowledgeBase = require(kbId);
        String fingerprint = configFingerprint(config);
        knowledgeBase.setIndexConfig(JsonUtil.toJson(config));
        knowledgeBase.setCurrentConfigFingerprint(fingerprint);
        if (retrievalConfig != null) {
            knowledgeBase.setRetrievalConfig(JsonUtil.toJson(retrievalConfig));
        }
        knowledgeBaseMapper.updateById(knowledgeBase);

        int stale = refreshStaleFlags(kbId, fingerprint);
        log.info("index config updated, kbId={}, fingerprint={}, staleDocuments={}", kbId, fingerprint, stale);
        return stale;
    }

    /**
     * Recomputes the {@code config_stale} flag of every document of a knowledge base.
     *
     * <p>The comparison is against the active version rather than the newest one: a document whose
     * latest build failed keeps serving its previous version, and that is the version whose freshness
     * the console reports. The flag is also cleared when it matches again, so reverting a
     * configuration change removes the warning without a rebuild.
     *
     * @param kbId        knowledge base business id
     * @param fingerprint fingerprint of the current configuration
     * @return number of documents left marked as stale
     */
    public int refreshStaleFlags(String kbId, String fingerprint) {
        List<Document> documents = documentMapper.selectList(new LambdaQueryWrapper<Document>()
                .eq(Document::getKbId, kbId)
                .isNotNull(Document::getCurrentVersionId));
        if (CollectionUtils.isEmpty(documents)) {
            return 0;
        }
        Map<String, String> fingerprintByVersion = activeFingerprints(documents);
        int stale = 0;
        for (Document document : documents) {
            boolean matches = fingerprint.equals(fingerprintByVersion.get(document.getCurrentVersionId()));
            int flag = matches ? FRESH : STALE;
            if (flag == STALE) {
                stale++;
            }
            documentMapper.update(null, new LambdaUpdateWrapper<Document>()
                    .set(Document::getConfigStale, flag)
                    .eq(Document::getDocId, document.getDocId()));
        }
        return stale;
    }

    /**
     * Soft deletes a knowledge base, its documents and its chunks, and clears the search engines.
     *
     * <p>Physical indices are left in place and marked for the cleanup task instead of being dropped
     * inline, so a mis-click never destroys the index definition; their documents are removed because
     * a soft deleted chunk that stayed searchable would still be returned by a query.
     *
     * @param kbId business id
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(String kbId) {
        KnowledgeBase knowledgeBase = require(kbId);
        removeChunks(kbId, new LambdaQueryWrapper<Chunk>().eq(Chunk::getKbId, kbId));
        documentMapper.delete(new LambdaQueryWrapper<Document>().eq(Document::getKbId, kbId));
        knowledgeBaseMapper.deleteById(knowledgeBase.getId());
        log.info("knowledge base deleted, kbId={}", kbId);
        // TODO(M4): schedule a CLEANUP task that drops the physical indices of this knowledge base.
    }

    /**
     * Removes the chunks matching a predicate from MySQL and from every engine.
     *
     * <p>The rows are selected once and then deleted by their own primary keys rather than by
     * re-running the predicate. Two reasons: the engines and the database then provably remove the same
     * set even if a concurrent write changes what the predicate matches in between, and the wrapper is
     * never handed to two different statements, which MyBatis-Plus does not guarantee to be safe.
     *
     * @param kbId      knowledge base business id
     * @param predicate chunk selection
     */
    public void removeChunks(String kbId, LambdaQueryWrapper<Chunk> predicate) {
        List<Chunk> chunks = chunkMapper.selectList(predicate);
        if (CollectionUtils.isEmpty(chunks)) {
            return;
        }
        List<String> chunkIds = chunks.stream().map(Chunk::getChunkId).toList();
        List<Long> rowIds = chunks.stream().map(Chunk::getId).toList();
        for (int start = 0; start < rowIds.size(); start += CHUNK_DELETE_BATCH) {
            chunkMapper.delete(new LambdaQueryWrapper<Chunk>().in(Chunk::getId,
                    rowIds.subList(start, Math.min(rowIds.size(), start + CHUNK_DELETE_BATCH))));
        }
        // Engine removal is irreversible, so it waits for the commit; see EngineChunkCleaner.
        engineChunkCleaner.removeAfterCommit(kbId, chunkIds);
    }

    /**
     * Fast-fails a configuration that selects the LLM semantic strategy without a usable chat model,
     * requirement section 4.3 "the strategy is not selectable without a chat model". The single gate
     * for this rule: nothing downstream - the splitter, the pipeline - re-checks it, so a knowledge
     * base can never end up configured for a strategy its deployment cannot run.
     *
     * @param config index configuration being written
     */
    private void requireSplitStrategyUsable(KbIndexConfig config) {
        if (config.getSplitStrategy() == null || config.getSplitStrategy().isBlank()) {
            return;
        }
        // Normalize before persisting. The column stores the raw string, so an uppercase or unknown
        // code written verbatim would silently bypass this gate and later fall through the splitter
        // router - the strategy would look configured while never running.
        SplitStrategy strategy = SplitStrategy.from(config.getSplitStrategy());
        if (strategy == null) {
            throw BizException.invalidParam(
                    "unknown split strategy: " + config.getSplitStrategy());
        }
        config.setSplitStrategy(strategy.code());
        if (strategy == SplitStrategy.LLM_SEMANTIC && !chatProvider.isConfigured()) {
            throw BizException.invalidParam(
                    "the LLM semantic split strategy requires a configured chat model");
        }
    }

    /**
     * Fingerprint of the whole configuration a build depends on.
     *
     * <p>Covers the parse inputs as well as the split ones, so changing a cleaning rule marks documents as
     * stale exactly like changing a chunk length does. The vision model is part of it because a different
     * model produces different image proxies, which is a content change even when nothing else moved.
     *
     * @param config index configuration
     * @return hexadecimal digest
     */
    public String configFingerprint(KbIndexConfig config) {
        return fingerprintFactory.configFingerprint(config, visionProvider.model());
    }

    /**
     * Builds the index configuration of a freshly created knowledge base from the deployment defaults.
     *
     * @return default index configuration
     */
    private KbIndexConfig defaultIndexConfig() {
        KbProperties.Split split = properties.getSplit();
        KbIndexConfig config = new KbIndexConfig();
        config.setSplitStrategy(FixedLengthTextSplitter.STRATEGY_CODE);
        config.setChunkMaxTokens(split.getMaxTokens());
        config.setChunkOverlap(split.getOverlapTokens());
        config.setEmbeddingModel(properties.getEmbedding().getModel());
        ParentChildParams parentChild = new ParentChildParams();
        parentChild.setEnabled(split.isParentChildEnabled());
        parentChild.setParentMaxTokens(split.getParentMaxTokens());
        parentChild.setChildMaxTokens(split.getChildMaxTokens());
        parentChild.setChildOverlap(split.getChildOverlap());
        config.setParentChild(parentChild);
        return config;
    }

    /**
     * Loads the split fingerprint of the active version of every document.
     *
     * <p>Batched for the same reason the chunk deletions are: a knowledge base with thousands of
     * documents would otherwise produce one {@code IN} list long enough to be rejected outright by some
     * MySQL configurations.
     *
     * @param documents documents with an active version
     * @return chunk fingerprint per version id
     */
    private Map<String, String> activeFingerprints(List<Document> documents) {
        List<String> versionIds = documents.stream().map(Document::getCurrentVersionId).toList();
        Map<String, String> byVersion = new HashMap<>(versionIds.size());
        for (int start = 0; start < versionIds.size(); start += CHUNK_DELETE_BATCH) {
            List<String> batch = versionIds.subList(start,
                    Math.min(versionIds.size(), start + CHUNK_DELETE_BATCH));
            List<DocumentVersion> versions = documentVersionMapper.selectList(
                    new LambdaQueryWrapper<DocumentVersion>().in(DocumentVersion::getVersionId, batch));
            for (DocumentVersion version : versions) {
                byVersion.put(version.getVersionId(), fingerprintFactory.versionFingerprint(
                        version.getParseFingerprint(), version.getChunkFingerprint()));
            }
        }
        return byVersion;
    }
}
