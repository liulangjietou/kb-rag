package io.kbrag.app.retrieval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import io.kbrag.app.alert.RetrievalDegradeMonitor;
import io.kbrag.app.index.EngineChunkCleaner;
import io.kbrag.app.index.IndexAliasManager;
import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.constant.ChunkMetadataKeys;
import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.entity.KnowledgeBase;
import io.kbrag.domain.enums.DegradedReason;
import io.kbrag.domain.enums.RetrievalSource;
import io.kbrag.domain.mapper.ChunkMapper;
import io.kbrag.domain.mapper.DocumentMapper;
import io.kbrag.domain.model.FulltextQuery;
import io.kbrag.domain.model.FusedChunk;
import io.kbrag.domain.model.KbIndexConfig;
import io.kbrag.domain.model.KbRetrievalConfig;
import io.kbrag.domain.model.RetrievalFilter;
import io.kbrag.domain.model.ScoredChunk;
import io.kbrag.domain.model.VectorQuery;
import io.kbrag.domain.port.EmbeddingProvider;
import io.kbrag.domain.port.FulltextStore;
import io.kbrag.domain.port.ObjectStorage;
import io.kbrag.domain.port.VectorStore;
import io.kbrag.domain.service.FusionRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Retrieval pipeline.
 *
 * <p>Fixed stage order, each stage optional but never reordered:
 * <pre>
 * rewrite -&gt; dual route recall (child level) -&gt; fusion -&gt; rerank -&gt; parent merge -&gt; threshold -&gt; top_n
 * </pre>
 *
 * <p><b>Why the order is fixed.</b> Rewriting after recall would be pointless, reranking before
 * fusion would rerank two lists that cannot be compared, merging before rerank would hide from the
 * cross encoder the very passage that matched, and thresholding before rerank would discard candidates
 * on a score the rerank stage is about to replace. Each stage may switch itself off; none may swap
 * places with another.
 *
 * <p><b>Two independent requests, not one hybrid query.</b> The BM25 route always runs; the vector
 * route runs whenever an embedding provider is configured, even in lite mode where both hit the same
 * Elasticsearch index. Issuing two requests is what yields two candidate sets with their own scores,
 * which a single hybrid request could not provide and without which fusion has nothing to fuse.
 *
 * <p><b>Mandatory filter.</b> Version visibility and the enabled flag are applied engine side and are
 * built here, out of reach of request parameters, so a caller can never recall a chunk of an archived
 * version. The optional metadata filter can only narrow that set further.
 *
 * <p><b>MySQL is the fact source.</b> Text is always read from the database and never from a search
 * engine, so a stale engine copy cannot reach a caller. An engine hit whose row is <em>gone</em> is
 * dropped and scheduled for removal, which makes every search a small repair pass. An engine hit whose
 * row merely says <em>disabled</em> is dropped and nothing else: the two look alike from the engine but
 * only one of them is garbage, and deleting the second would destroy a chunk an operator can re-enable.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalService {

    private static final String META_FUSED_SCORE = "fused_score";
    private static final String META_VECTOR_SCORE = "vector_score";
    private static final String META_BM25_SCORE = "bm25_score";
    private static final String META_NORM_VECTOR_SCORE = "norm_vector_score";
    private static final String META_NORM_BM25_SCORE = "norm_bm25_score";
    private static final String META_RERANK_SCORE = "rerank_score";
    private static final String META_VECTOR_RANK = "vector_rank";
    private static final String META_BM25_RANK = "bm25_rank";
    private static final String META_CHUNK_SEQ = "chunk_seq";
    private static final String META_CHILD_IDS = "child_ids";
    private static final String META_DISABLED_CHILD_IDS = "disabled_child_ids";
    private static final String META_CHILDREN = "children";
    private static final String META_CHILD_CHUNK_ID = "chunk_id";
    private static final String META_CHILD_CONTENT = "content";
    private static final String META_CHILD_SCORE = "score";
    private static final String META_CHILD_SCORE_TYPE = "score_type";

    private static final int ENABLED = 1;

    private final KnowledgeBaseService knowledgeBaseService;
    private final DocumentMapper documentMapper;
    private final ChunkMapper chunkMapper;
    private final FulltextStore fulltextStore;
    private final VectorStore vectorStore;
    private final EmbeddingProvider embeddingProvider;
    private final IndexAliasManager indexAliasManager;
    private final FusionRouter fusionRouter;
    private final RewriteService rewriteService;
    private final RerankService rerankService;
    private final ScoreThresholdPolicy scoreThresholdPolicy;
    private final ParentChildMerger parentChildMerger;
    private final DisabledChildVisibility disabledChildVisibility;
    private final EngineChunkCleaner engineChunkCleaner;
    private final ObjectStorage objectStorage;
    private final RetrievalDegradeMonitor degradeMonitor;
    private final KbProperties properties;

    /**
     * Runs one retrieval call.
     *
     * @param kbId    knowledge base business id
     * @param command request parameters, every tuning field optional
     * @return ordered nodes, degradation markers and the applied parameter summary
     */
    public SearchOutcome search(String kbId, RetrievalCommand command) {
        KnowledgeBase knowledgeBase = knowledgeBaseService.require(kbId);
        KbIndexConfig indexConfig = knowledgeBaseService.indexConfigOf(knowledgeBase);
        KbRetrievalConfig kbRetrieval = JsonUtil.parse(knowledgeBase.getRetrievalConfig(), KbRetrievalConfig.class);
        RetrievalSettings settings = RetrievalSettings.resolve(command, kbRetrieval, properties.getRetrieval());

        List<String> degraded = new ArrayList<>();
        RewriteOutcome rewrite = rewriteService.rewrite(command.getQuery(), command.getMessages(),
                shouldRun(settings.isRewriteEnabled(), rewriteService.isAvailable(),
                        command.getRewriteEnabled(), kbRetrieval == null ? null : kbRetrieval.getRewriteEnabled()));
        addMarker(degraded, rewrite.getDegradedReason());
        String effectiveQuery = rewrite.getQuery();

        List<String> visibleVersionIds = visibleVersionIds(kbId);
        if (CollectionUtils.isEmpty(visibleVersionIds)) {
            log.info("no active document version, kbId={}", kbId);
            degradeMonitor.record(!degraded.isEmpty());
            return new SearchOutcome(List.of(), degraded, applied(effectiveQuery, settings,
                    ThresholdTarget.NONE.code()));
        }

        RetrievalFilter filter = RetrievalFilter.builder()
                .kbId(kbId)
                .documentVersionIds(visibleVersionIds)
                .enabledOnly(true)
                .metadataFilter(command.getMetadataFilter())
                .build();

        boolean vectorRouteRan = embeddingProvider.isConfigured();
        Map<RetrievalSource, List<ScoredChunk>> routeResults = recall(kbId, effectiveQuery, filter,
                settings.getRecallTopK(), vectorRouteRan);
        if (!vectorRouteRan) {
            addMarker(degraded, DegradedReason.VECTOR_ROUTE_UNAVAILABLE.code());
        }

        List<FusedChunk> fused = fusionRouter.fuse(routeResults, settings.getFusion());
        Map<String, Chunk> chunkById = loadChunks(fused);
        List<FusedChunk> live = dropOrphans(kbId, fused, chunkById);
        live = dropDisabled(live, chunkById);

        List<RetrievalCandidate> candidates = selectCandidates(live, chunkById, indexConfig, settings);
        applyRerank(effectiveQuery, candidates, settings, command, kbRetrieval, degraded);

        candidates.sort(Comparator.comparingDouble(RetrievalCandidate::orderingScore).reversed()
                .thenComparing(RetrievalCandidate::chunkId));
        List<RetrievalUnit> units = parentChildMerger.merge(candidates);
        DisabledChildVisibility.Visibility visibility = disabledChildVisibility.apply(units,
                disabledChildVisibility.disabledChildrenOf(parentIdsOf(units)),
                indexConfig.isHideParentWithDisabledChild());
        units = visibility.units();

        boolean rerankApplied = !candidates.isEmpty() && candidates.get(0).getRerankScore() != null;
        ScoreThresholdPolicy.ThresholdDecision decision =
                scoreThresholdPolicy.decide(settings.getScoreThreshold(), rerankApplied, vectorRouteRan);
        if (decision.isInactive()) {
            addMarker(degraded, DegradedReason.THRESHOLD_INACTIVE.code());
        }
        units = applyThreshold(units, decision);
        if (units.size() > settings.getTopN()) {
            units = units.subList(0, settings.getTopN());
        }

        List<RetrievalNodeView> nodes = toNodes(units, decision, settings,
                visibility.disabledChildIdsByUnit());
        log.info("search finished, kbId={}, recallTopK={}, topN={}, fusion={}, candidates={}, "
                        + "units={}, returned={}, rerank={}, degraded={}",
                kbId, settings.getRecallTopK(), settings.getTopN(), settings.getFusion().getMode().code(),
                candidates.size(), units.size(), nodes.size(), rerankApplied, degraded);
        degradeMonitor.record(!degraded.isEmpty());
        return new SearchOutcome(nodes, degraded, applied(effectiveQuery, settings, decision.appliedOn()));
    }

    /**
     * Decides whether an optional stage runs.
     *
     * <p>A stage that nobody asked for is skipped silently when its model is missing, because a
     * deployment without that model is a supported configuration rather than a fault. A stage somebody
     * did ask for still runs into the missing model on purpose, so the response can carry the marker
     * explaining why the request was not honoured.
     *
     * @param enabled        effective switch after resolving the configuration layers
     * @param available      {@code true} when the backing model is configured
     * @param requestValue   request level switch, {@code null} when unspecified
     * @param kbValue        knowledge base level switch, {@code null} when unspecified
     * @return {@code true} when the stage should be invoked
     */
    private boolean shouldRun(boolean enabled, boolean available, Boolean requestValue, Boolean kbValue) {
        boolean explicitlyRequested = Boolean.TRUE.equals(requestValue)
                || (requestValue == null && Boolean.TRUE.equals(kbValue));
        return enabled && (available || explicitlyRequested);
    }

    private Map<RetrievalSource, List<ScoredChunk>> recall(String kbId, String query, RetrievalFilter filter,
                                                           int recallTopK, boolean vectorRouteRan) {
        Map<RetrievalSource, List<ScoredChunk>> routeResults = new EnumMap<>(RetrievalSource.class);
        routeResults.put(RetrievalSource.BM25, fulltextStore.searchBm25(
                indexAliasManager.fulltextAlias(kbId),
                FulltextQuery.builder().queryText(query).topK(recallTopK).filter(filter).build()));
        if (vectorRouteRan) {
            float[] queryVector = embeddingProvider.embed(List.of(query)).get(0);
            routeResults.put(RetrievalSource.VECTOR, vectorStore.search(
                    indexAliasManager.vectorAlias(kbId),
                    VectorQuery.builder().queryVector(queryVector).topK(recallTopK).filter(filter).build()));
        }
        return routeResults;
    }

    /**
     * Cuts the fused list down to the candidates worth carrying into the rerank and merge stages.
     *
     * @param live        fused candidates that still exist in MySQL
     * @param chunkById   fact source rows
     * @param indexConfig knowledge base index configuration
     * @param settings    effective retrieval parameters
     * @return mutable candidate list in fusion order
     */
    private List<RetrievalCandidate> selectCandidates(List<FusedChunk> live, Map<String, Chunk> chunkById,
                                                      KbIndexConfig indexConfig, RetrievalSettings settings) {
        int maxCandidates = Math.min(rerankService.candidateLimit(), live.size());
        int count = maxCandidates;
        if (indexConfig.parentChildEnabled()) {
            KbProperties.Retrieval retrieval = properties.getRetrieval();
            int parentTarget = parentChildMerger.parentTarget(settings.getTopN(),
                    retrieval.getParentCandidateFactor(), retrieval.getParentCandidateFloor());
            Map<String, String> parentIdByChunk = new HashMap<>(chunkById.size());
            chunkById.forEach((chunkId, chunk) -> parentIdByChunk.put(chunkId, chunk.getParentId()));
            count = parentChildMerger.candidateCount(live, parentIdByChunk, parentTarget, maxCandidates);
        }
        List<RetrievalCandidate> candidates = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            FusedChunk fusedChunk = live.get(i);
            candidates.add(new RetrievalCandidate(fusedChunk, chunkById.get(fusedChunk.getChunkId())));
        }
        return candidates;
    }

    private void applyRerank(String query, List<RetrievalCandidate> candidates, RetrievalSettings settings,
                             RetrievalCommand command, KbRetrievalConfig kbRetrieval, List<String> degraded) {
        boolean run = shouldRun(settings.isRerankEnabled(), rerankService.isAvailable(),
                command.getRerankEnabled(), kbRetrieval == null ? null : kbRetrieval.getRerankEnabled());
        List<String> documents = candidates.stream().map(candidate -> candidate.getChunk().getContent()).toList();
        RerankOutcome outcome = rerankService.rerank(query, documents, run);
        addMarker(degraded, outcome.getDegradedReason());
        if (!outcome.isApplied()) {
            return;
        }
        for (int i = 0; i < candidates.size(); i++) {
            candidates.get(i).applyRerankScore(outcome.getScores().get(i));
        }
    }

    /**
     * Applies the absolute threshold to the merged units.
     *
     * <p>The unit is judged by its best member, the same one that placed it where it is in the
     * ranking: filtering on evidence that did not contribute to the rank would let a unit survive on a
     * passage nobody is going to read.
     *
     * @param units    merged units ordered by descending score
     * @param decision threshold decision of this search
     * @return surviving units in the same order
     */
    private List<RetrievalUnit> applyThreshold(List<RetrievalUnit> units,
                                               ScoreThresholdPolicy.ThresholdDecision decision) {
        if (!decision.isActive()) {
            return units;
        }
        List<RetrievalUnit> kept = new ArrayList<>(units.size());
        for (RetrievalUnit unit : units) {
            Double score = scoreThresholdPolicy.thresholdScore(unit.best(), decision);
            if (score != null && score >= decision.getThreshold()) {
                kept.add(unit);
            }
        }
        log.info("threshold applied on {}, threshold={}, before={}, after={}",
                decision.getTarget(), decision.getThreshold(), units.size(), kept.size());
        return kept;
    }

    /**
     * Collects the version visibility set of a knowledge base.
     *
     * <p>Management console calls have no application version context, so the set is the current
     * active version of every document.
     *
     * @param kbId knowledge base business id
     * @return active document version ids
     */
    private List<String> visibleVersionIds(String kbId) {
        List<Document> documents = documentMapper.selectList(new LambdaQueryWrapper<Document>()
                .eq(Document::getKbId, kbId)
                .isNotNull(Document::getCurrentVersionId));
        // TODO(M4): cache the visibility set per knowledge base once document counts grow.
        return documents.stream().map(Document::getCurrentVersionId).toList();
    }

    /**
     * Loads the fact source rows of the recalled candidates.
     *
     * <p><b>Disabled rows are loaded, not filtered out here.</b> The engine side predicate already
     * excludes them, so a disabled row reaching this point means the engine copy of its flag is stale -
     * which happens legitimately, for instance on the Milvus route where no partial update exists.
     * Filtering it out in the query would make it indistinguishable from a row that no longer exists,
     * and the self healing path would then delete a perfectly valid chunk from the engines, so
     * re-enabling it later would return nothing until the next rebuild. The two cases are therefore kept
     * apart: absent rows are repaired, disabled ones are merely dropped from the ranking.
     *
     * @param fused fused candidates
     * @return fact source row per chunk id, disabled rows included
     */
    private Map<String, Chunk> loadChunks(List<FusedChunk> fused) {
        if (CollectionUtils.isEmpty(fused)) {
            return Map.of();
        }
        List<String> chunkIds = fused.stream().map(FusedChunk::getChunkId).toList();
        List<Chunk> chunks = chunkMapper.selectList(new LambdaQueryWrapper<Chunk>()
                .in(Chunk::getChunkId, chunkIds));
        Map<String, Chunk> chunkById = new HashMap<>(chunks.size());
        for (Chunk chunk : chunks) {
            chunkById.put(chunk.getChunkId(), chunk);
        }
        return chunkById;
    }

    /**
     * Drops candidates whose fact source row is disabled, without touching the engines.
     *
     * @param live      candidates backed by a live row
     * @param chunkById fact source rows
     * @return candidates that may take part in the ranking, in the original order
     */
    private List<FusedChunk> dropDisabled(List<FusedChunk> live, Map<String, Chunk> chunkById) {
        List<FusedChunk> enabled = new ArrayList<>(live.size());
        int dropped = 0;
        for (FusedChunk candidate : live) {
            Chunk chunk = chunkById.get(candidate.getChunkId());
            if (chunk.getEnabled() != null && chunk.getEnabled() == ENABLED) {
                enabled.add(candidate);
            } else {
                dropped++;
            }
        }
        if (dropped > 0) {
            log.info("recalled chunks dropped because the fact source disabled them, count={}", dropped);
        }
        return enabled;
    }

    /**
     * Parent chunk ids of the merged units.
     *
     * @param units merged units
     * @return parent unit ids, empty for a single level knowledge base
     */
    private List<String> parentIdsOf(List<RetrievalUnit> units) {
        return units.stream().filter(RetrievalUnit::isParent).map(RetrievalUnit::getUnitId).toList();
    }

    /**
     * Drops engine hits the fact source no longer owns and schedules their removal.
     *
     * @param kbId      knowledge base business id
     * @param fused     fused candidates
     * @param chunkById fact source rows that were found
     * @return candidates backed by a live row, in the original order
     */
    private List<FusedChunk> dropOrphans(String kbId, List<FusedChunk> fused, Map<String, Chunk> chunkById) {
        List<FusedChunk> live = new ArrayList<>(fused.size());
        List<String> orphans = new ArrayList<>();
        for (FusedChunk candidate : fused) {
            if (chunkById.containsKey(candidate.getChunkId())) {
                live.add(candidate);
            } else {
                orphans.add(candidate.getChunkId());
            }
        }
        if (!orphans.isEmpty()) {
            log.info("engine hits without a live fact source row, kbId={}, count={}", kbId, orphans.size());
            engineChunkCleaner.removeAsync(kbId, orphans);
        }
        return live;
    }

    private List<RetrievalNodeView> toNodes(List<RetrievalUnit> units,
                                            ScoreThresholdPolicy.ThresholdDecision decision,
                                            RetrievalSettings settings,
                                            Map<String, List<String>> disabledChildIdsByUnit) {
        if (CollectionUtils.isEmpty(units)) {
            return List.of();
        }
        Map<String, Chunk> parentById = loadParents(units);
        List<RetrievalNodeView> nodes = new ArrayList<>(units.size());
        for (RetrievalUnit unit : units) {
            RetrievalCandidate best = unit.best();
            ScoreThresholdPolicy.ReportedScore reported =
                    scoreThresholdPolicy.report(best, decision, settings.getFusion().getMode());
            Chunk answerChunk = unit.isParent()
                    ? parentById.getOrDefault(unit.getUnitId(), best.getChunk())
                    : best.getChunk();
            nodes.add(RetrievalNodeView.builder()
                    .docId(answerChunk.getDocId())
                    .documentVersionId(answerChunk.getDocumentVersionId())
                    .chunkId(answerChunk.getChunkId())
                    .chunkType(answerChunk.getChunkType().code())
                    .content(answerChunk.getContent())
                    .score(reported.getValue())
                    .scoreType(reported.getType().code())
                    .retrievalSource(best.getFused().getPrimarySource().code())
                    .metadata(buildMetadata(unit, answerChunk, decision, settings,
                            disabledChildIdsByUnit.get(unit.getUnitId())))
                    .imageUrls(presignedImageUrls(unit, answerChunk))
                    .previewUrl(null)
                    .build());
        }
        return nodes;
    }

    /**
     * Turns the stored image keys of a result into time limited pre signed URLs.
     *
     * <p>The keys are minted per response rather than stored as URLs: a link that lived in the index would
     * be public for as long as the index exists, and it would already be expired by the time it is read.
     *
     * <p>A two level result collects the keys of its matched children as well as its own, because the parent
     * text is what is returned and the image that made a child match is part of that text.
     *
     * @param unit        merged unit being returned
     * @param answerChunk chunk whose text is returned
     * @return pre signed URLs, empty when the result derives from no image
     */
    private List<String> presignedImageUrls(RetrievalUnit unit, Chunk answerChunk) {
        List<String> keys = new ArrayList<>(imageKeysOf(answerChunk));
        if (unit.isParent()) {
            for (RetrievalCandidate member : unit.getMembers()) {
                for (String key : imageKeysOf(member.getChunk())) {
                    if (!keys.contains(key)) {
                        keys.add(key);
                    }
                }
            }
        }
        if (CollectionUtils.isEmpty(keys)) {
            return List.of();
        }
        Duration ttl = Duration.ofMinutes(properties.getMinio().getPresignedTtlMinutes());
        List<String> urls = new ArrayList<>(keys.size());
        for (String key : keys) {
            try {
                urls.add(objectStorage.presignedUrl(key, ttl));
            } catch (Exception e) {
                // A thumbnail that cannot be signed must not fail a search: the passage is the answer.
                log.info("image could not be presigned for a search result, object={}", key);
            }
        }
        return urls;
    }

    /**
     * Reads the image keys a chunk recorded.
     *
     * @param chunk fact source row
     * @return object storage keys, empty when the chunk derives from no image
     */
    private List<String> imageKeysOf(Chunk chunk) {
        Object value = storedMetadata(chunk).get(ChunkMetadataKeys.IMAGE_URLS);
        if (!(value instanceof List<?> items)) {
            return List.of();
        }
        List<String> keys = new ArrayList<>(items.size());
        for (Object item : items) {
            if (item != null) {
                keys.add(String.valueOf(item));
            }
        }
        return keys;
    }

    /**
     * Parses the metadata column of a chunk.
     *
     * @param chunk fact source row
     * @return metadata document, empty when the column is blank
     */
    private Map<String, Object> storedMetadata(Chunk chunk) {
        if (chunk == null || chunk.getMetadata() == null || chunk.getMetadata().isBlank()) {
            return Map.of();
        }
        Map<String, Object> parsed = JsonUtil.parse(chunk.getMetadata(),
                new TypeReference<Map<String, Object>>() {
                });
        return parsed == null ? Map.of() : parsed;
    }

    /**
     * Loads the parent rows of the two level units.
     *
     * <p>Parents are never indexed, so their text only exists in MySQL; this is the single reason the
     * engines can stay child only while the caller still receives a passage large enough to read.
     *
     * @param units merged units
     * @return parent chunk per parent chunk id
     */
    private Map<String, Chunk> loadParents(List<RetrievalUnit> units) {
        List<String> parentIds = units.stream()
                .filter(RetrievalUnit::isParent)
                .map(RetrievalUnit::getUnitId)
                .toList();
        if (CollectionUtils.isEmpty(parentIds)) {
            return Map.of();
        }
        List<Chunk> parents = chunkMapper.selectList(new LambdaQueryWrapper<Chunk>()
                .in(Chunk::getChunkId, parentIds));
        Map<String, Chunk> parentById = new HashMap<>(parents.size());
        for (Chunk parent : parents) {
            parentById.put(parent.getChunkId(), parent);
        }
        return parentById;
    }

    private Map<String, Object> buildMetadata(RetrievalUnit unit, Chunk answerChunk,
                                              ScoreThresholdPolicy.ThresholdDecision decision,
                                              RetrievalSettings settings,
                                              List<String> disabledChildIds) {
        Map<String, Object> metadata = new LinkedHashMap<>(scoreMetadata(unit.best()));
        metadata.put(META_CHUNK_SEQ, answerChunk.getSeq());
        // The stored document facts travel next to the scores so a chat result card can show its
        // conversation, its senders and its time without a second round trip. The image keys are excluded:
        // they leave through image_urls as pre signed links and the raw keys are of no use to a caller.
        storedMetadata(answerChunk).forEach((key, value) -> {
            if (!ChunkMetadataKeys.IMAGE_URLS.equals(key)) {
                metadata.putIfAbsent(key, value);
            }
        });
        if (!unit.isParent()) {
            return metadata;
        }
        if (CollectionUtils.isNotEmpty(disabledChildIds)) {
            // The parent text is returned in full, so the caller is told which passages inside it an
            // operator excluded rather than being left to assume the whole parent is approved.
            metadata.put(META_DISABLED_CHILD_IDS, disabledChildIds);
        }
        metadata.put(META_CHILD_IDS, unit.getMembers().stream().map(RetrievalCandidate::chunkId).toList());
        List<Map<String, Object>> children = new ArrayList<>(unit.getMembers().size());
        for (RetrievalCandidate member : unit.getMembers()) {
            ScoreThresholdPolicy.ReportedScore reported =
                    scoreThresholdPolicy.report(member, decision, settings.getFusion().getMode());
            Map<String, Object> child = new LinkedHashMap<>();
            child.put(META_CHILD_CHUNK_ID, member.chunkId());
            child.put(META_CHILD_CONTENT, member.getChunk().getContent());
            child.put(META_CHILD_SCORE, reported.getValue());
            child.put(META_CHILD_SCORE_TYPE, reported.getType().code());
            child.putAll(scoreMetadata(member));
            children.add(child);
        }
        metadata.put(META_CHILDREN, children);
        return metadata;
    }

    /**
     * Per route evidence of one candidate, shared by the node metadata and by the child detail list.
     *
     * <p>Both the raw and the normalised score are exposed when they exist: the raw value is what the
     * engine reported and is the only one comparable with a manual query, while the normalised value
     * is what the weighted strategy actually summed, and a debug page that shows one without the other
     * cannot explain a ranking.
     *
     * @param candidate candidate being described
     * @return score keys, ordered for readability
     */
    private Map<String, Object> scoreMetadata(RetrievalCandidate candidate) {
        FusedChunk fused = candidate.getFused();
        Map<String, Object> scores = new LinkedHashMap<>();
        scores.put(META_FUSED_SCORE, fused.getFusedScore());
        putIfPresent(scores, META_VECTOR_SCORE, fused.routeScore(RetrievalSource.VECTOR));
        putIfPresent(scores, META_VECTOR_RANK, fused.getRouteRanks().get(RetrievalSource.VECTOR));
        putIfPresent(scores, META_NORM_VECTOR_SCORE, fused.normalizedScore(RetrievalSource.VECTOR));
        putIfPresent(scores, META_BM25_SCORE, fused.routeScore(RetrievalSource.BM25));
        putIfPresent(scores, META_BM25_RANK, fused.getRouteRanks().get(RetrievalSource.BM25));
        putIfPresent(scores, META_NORM_BM25_SCORE, fused.normalizedScore(RetrievalSource.BM25));
        putIfPresent(scores, META_RERANK_SCORE, candidate.getRerankScore());
        return scores;
    }

    private AppliedInfo applied(String usedQuery, RetrievalSettings settings, String thresholdAppliedOn) {
        return AppliedInfo.builder()
                .rewriteUsedQuery(usedQuery)
                .fusionMode(settings.getFusion().getMode().code())
                .thresholdAppliedOn(thresholdAppliedOn)
                .build();
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private void addMarker(List<String> degraded, String marker) {
        if (marker != null && !degraded.contains(marker)) {
            degraded.add(marker);
        }
    }
}
