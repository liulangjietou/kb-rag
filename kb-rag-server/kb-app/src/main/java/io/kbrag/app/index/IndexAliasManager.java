package io.kbrag.app.index;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.common.constant.KbConstants;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.IndexRegistry;
import io.kbrag.domain.enums.IndexRegistryStatus;
import io.kbrag.domain.enums.VectorEngine;
import io.kbrag.domain.mapper.IndexRegistryMapper;
import io.kbrag.domain.model.ChunkRecord;
import io.kbrag.domain.model.IndexSpec;
import io.kbrag.domain.port.EmbeddingProvider;
import io.kbrag.domain.port.FulltextStore;
import io.kbrag.domain.port.VectorStore;
import io.kbrag.domain.service.IndexNaming;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Owns physical index creation, alias binding and the registry rows behind them.
 *
 * <p>Every read and write in the service addresses an alias, never a physical index. The registry
 * table is the single source of truth of which physical index an alias currently points at, which is
 * what makes an embedding model switch or a snapshot publish the very same operation: create a new
 * physical index, backfill it, repoint the alias.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndexAliasManager {

    private static final int CURRENT = 1;

    private final KbProperties properties;
    private final IndexNaming indexNaming;
    private final EmbeddingProvider embeddingProvider;
    private final FulltextStore fulltextStore;
    private final VectorStore vectorStore;
    private final IndexRegistryMapper indexRegistryMapper;

    /**
     * Resolves the write targets of a knowledge base for the current deployment shape.
     *
     * @param kbId knowledge base business id
     * @return targets in write order, Elasticsearch first
     */
    public List<IndexTarget> resolveTargets(String kbId) {
        VectorEngine engine = properties.getVector().resolved();
        boolean embeddingConfigured = embeddingProvider.isConfigured();
        String embeddingSegment = embeddingConfigured
                ? indexNaming.embeddingSegment(embeddingProvider.model())
                : KbConstants.EMBEDDING_SEGMENT_NONE;

        String fulltextIndex = indexNaming.fulltextPhysicalName(kbId, engine, embeddingSegment);
        String fulltextAlias = indexNaming.alias(kbId, VectorEngine.ES);

        List<IndexTarget> targets = new ArrayList<>(2);
        if (!embeddingConfigured) {
            targets.add(new IndexTarget(VectorEngine.ES, fulltextIndex, fulltextAlias,
                    KbConstants.EMBEDDING_SEGMENT_NONE, false, 0));
            return targets;
        }
        int dimension = embeddingProvider.dimension();
        if (engine == VectorEngine.ES) {
            // Lite mode: one Elasticsearch index serves BM25 and kNN, so it carries the vector field.
            targets.add(new IndexTarget(VectorEngine.ES, fulltextIndex, fulltextAlias,
                    embeddingSegment, true, dimension));
            return targets;
        }
        targets.add(new IndexTarget(VectorEngine.ES, fulltextIndex, fulltextAlias,
                KbConstants.EMBEDDING_SEGMENT_BM25, false, 0));
        targets.add(new IndexTarget(VectorEngine.QDRANT,
                indexNaming.vectorPhysicalName(kbId, embeddingSegment),
                indexNaming.alias(kbId, VectorEngine.QDRANT),
                embeddingSegment,
                true,
                dimension));
        return targets;
    }

    /**
     * Alias of the BM25 route.
     *
     * @param kbId knowledge base business id
     * @return Elasticsearch alias
     */
    public String fulltextAlias(String kbId) {
        return indexNaming.alias(kbId, VectorEngine.ES);
    }

    /**
     * Alias of the vector route.
     *
     * @param kbId knowledge base business id
     * @return alias of the configured vector engine, or {@code null} in zero key mode
     */
    public String vectorAlias(String kbId) {
        if (!embeddingProvider.isConfigured()) {
            return null;
        }
        return indexNaming.alias(kbId, properties.getVector().resolved());
    }

    /**
     * Creates every physical index of a knowledge base, binds the aliases and registers the rows.
     * Safe to call on every upload.
     *
     * @param kbId knowledge base business id
     * @return the targets that were ensured
     */
    public List<IndexTarget> ensureIndexes(String kbId) {
        List<IndexTarget> targets = resolveTargets(kbId);
        for (IndexTarget target : targets) {
            IndexSpec spec = IndexSpec.builder()
                    .physicalIndexName(target.physicalIndexName())
                    .aliasName(target.aliasName())
                    .dimension(target.carriesVector() ? target.dimension() : null)
                    .schemaVersion(KbConstants.INDEX_SCHEMA_VERSION)
                    .build();
            if (target.engine() == VectorEngine.ES) {
                fulltextStore.ensureIndex(spec);
            } else {
                vectorStore.ensureIndex(spec);
            }
            register(kbId, target);
        }
        return targets;
    }

    /**
     * Writes chunk records to a target through its alias, including the vector only when the target
     * declares a vector field.
     *
     * @param target  write target
     * @param records records to write
     */
    public void write(IndexTarget target, List<ChunkRecord> records) {
        if (CollectionUtils.isEmpty(records)) {
            return;
        }
        if (target.engine() == VectorEngine.ES) {
            fulltextStore.upsert(target.aliasName(), records);
        } else {
            vectorStore.upsert(target.aliasName(), records);
        }
    }

    /**
     * Removes chunks from every target of a knowledge base, used before rebuilding a version.
     *
     * @param kbId     knowledge base business id
     * @param chunkIds chunk ids to remove
     */
    public void deleteEverywhere(String kbId, List<String> chunkIds) {
        if (CollectionUtils.isEmpty(chunkIds)) {
            return;
        }
        for (IndexTarget target : resolveTargets(kbId)) {
            if (target.engine() == VectorEngine.ES) {
                fulltextStore.delete(target.aliasName(), chunkIds);
            } else {
                vectorStore.delete(target.aliasName(), chunkIds);
            }
        }
    }

    /**
     * Mirrors the retrieval switch of chunks into every target of a knowledge base.
     *
     * <p>Deliberately not expressed as a write: an upsert replaces the whole engine document, and in
     * lite mode that document also carries the embedding, which no MySQL row can supply. Flipping the
     * flag on its own is what makes disabling a chunk free of a re-embedding.
     *
     * @param kbId     knowledge base business id
     * @param chunkIds chunk ids to update, ignored when empty
     * @param enabled  new retrieval switch value
     */
    public void updateEnabledEverywhere(String kbId, List<String> chunkIds, boolean enabled) {
        if (CollectionUtils.isEmpty(chunkIds)) {
            return;
        }
        for (IndexTarget target : resolveTargets(kbId)) {
            if (target.engine() == VectorEngine.ES) {
                fulltextStore.updateEnabled(target.aliasName(), chunkIds, enabled);
            } else {
                vectorStore.updateEnabled(target.aliasName(), chunkIds, enabled);
            }
        }
    }

    private void register(String kbId, IndexTarget target) {
        IndexRegistry existing = indexRegistryMapper.selectOne(new LambdaQueryWrapper<IndexRegistry>()
                .eq(IndexRegistry::getPhysicalIndexName, target.physicalIndexName())
                .last("limit 1"));
        if (existing != null) {
            return;
        }
        IndexRegistry registry = new IndexRegistry();
        registry.setKbId(kbId);
        registry.setEngine(target.engine().code());
        registry.setPhysicalIndexName(target.physicalIndexName());
        registry.setAliasName(target.aliasName());
        registry.setIsCurrent(CURRENT);
        registry.setEmbeddingProvider(embeddingProvider.providerName());
        registry.setEmbeddingModel(embeddingProvider.model());
        registry.setEmbeddingVersion(target.embeddingSegment());
        registry.setSnapshotVersion(KbConstants.SNAPSHOT_SEGMENT_V1);
        registry.setSchemaVersion(KbConstants.INDEX_SCHEMA_VERSION);
        registry.setStatus(IndexRegistryStatus.ACTIVE);
        indexRegistryMapper.insert(registry);
        log.info("index registered, kbId={}, engine={}, index={}, alias={}",
                kbId, target.engine().code(), target.physicalIndexName(), target.aliasName());
    }
}
