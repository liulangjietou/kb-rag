package io.kbrag.app.retrieval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.app.index.EngineChunkCleaner;
import io.kbrag.app.index.IndexAliasManager;
import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.config.KbProperties;
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
import io.kbrag.domain.port.VectorStore;
import io.kbrag.domain.service.FusionRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

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
 * engine, so a stale engine copy cannot reach a caller. An engine hit whose row is gone or disabled is
 * dropped from the result and scheduled for removal, which makes every search a small repair pass.
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
    private final EngineChunkCleaner engineChunkCleaner;
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

        List<RetrievalCandidate> candidates = selectCandidates(live, chunkById, indexConfig, settings);
        applyRerank(effectiveQuery, candidates, settings, command, kbRetrieval, degraded);

        candidates.sort(Comparator.comparingDouble(RetrievalCandidate::orderingScore).reversed()
                .thenComparing(RetrievalCandidate::chunkId));
        List<RetrievalUnit> units = parentChildMerger.merge(candidates);

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

        List<RetrievalNodeView> nodes = toNodes(units, decision, settings);
        log.info("search finished, kbId={}, recallTopK={}, topN={}, fusion={}, candidates={}, "
                        + "units={}, returned={}, rerank={}, degraded={}",
                kbId, settings.getRecallTopK(), settings.getTopN(), settings.getFusion().getMode().code(),
                candidates.size(), units.size(), nodes.size(), rerankApplied, degraded);
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

    private Map<String, Chunk> loadChunks(List<FusedChunk> fused) {
        if (CollectionUtils.isEmpty(fused)) {
            return Map.of();
        }
        List<String> chunkIds = fused.stream().map(FusedChunk::getChunkId).toList();
        List<Chunk> chunks = chunkMapper.selectList(new LambdaQueryWrapper<Chunk>()
                .in(Chunk::getChunkId, chunkIds)
                .eq(Chunk::getEnabled, ENABLED));
        Map<String, Chunk> chunkById = new HashMap<>(chunks.size());
        for (Chunk chunk : chunks) {
            chunkById.put(chunk.getChunkId(), chunk);
        }
        return chunkById;
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
                                            RetrievalSettings settings) {
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
                    .metadata(buildMetadata(unit, answerChunk, decision, settings))
                    .imageUrls(List.of())
                    .previewUrl(null)
                    .build());
        }
        return nodes;
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
                                              RetrievalSettings settings) {
        Map<String, Object> metadata = new LinkedHashMap<>(scoreMetadata(unit.best()));
        metadata.put(META_CHUNK_SEQ, answerChunk.getSeq());
        if (!unit.isParent()) {
            return metadata;
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
