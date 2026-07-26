package io.kbrag.app.retrieval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import io.kbrag.app.alert.RetrievalDegradeMonitor;
import io.kbrag.app.index.EngineChunkCleaner;
import io.kbrag.app.index.IndexAliasManager;
import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.constant.ChunkMetadataKeys;
import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.entity.KnowledgeBase;
import io.kbrag.domain.enums.DegradedReason;
import io.kbrag.domain.enums.FusionMode;
import io.kbrag.domain.enums.RetrievalSource;
import io.kbrag.domain.mapper.ChunkMapper;
import io.kbrag.domain.mapper.DocumentMapper;
import io.kbrag.domain.model.FulltextQuery;
import io.kbrag.domain.model.FusedChunk;
import io.kbrag.domain.model.KbIndexConfig;
import io.kbrag.domain.model.KbRef;
import io.kbrag.domain.model.KbRetrievalConfig;
import io.kbrag.domain.model.RetrievalFilter;
import io.kbrag.domain.model.ScoredChunk;
import io.kbrag.domain.model.VectorQuery;
import io.kbrag.domain.port.EmbeddingProvider;
import io.kbrag.domain.port.FulltextStore;
import io.kbrag.domain.port.ObjectStorage;
import io.kbrag.domain.port.VectorStore;
import io.kbrag.domain.service.CrossKbRrfFusion;
import io.kbrag.domain.service.FusionRouter;
import io.kbrag.domain.service.KbQuotaAllocator;
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
import java.util.Set;

/**
 * Retrieval pipeline.
 *
 * <p>Fixed stage order, each stage optional but never reordered:
 * <pre>
 * rewrite -&gt; routing -&gt; dual route recall (child level) -&gt; in base fusion -&gt; cross base fusion
 *         -&gt; rerank -&gt; parent merge -&gt; threshold -&gt; top_n
 * </pre>
 *
 * <p><b>Why the order is fixed.</b> Rewriting after recall would be pointless, routing before the
 * rewrite would decide on a question whose references are still unresolved, reranking before
 * fusion would rerank two lists that cannot be compared, merging before rerank would hide from the
 * cross encoder the very passage that matched, and thresholding before rerank would discard candidates
 * on a score the rerank stage is about to replace. Each stage may switch itself off; none may swap
 * places with another.
 *
 * <p><b>One base or fifteen, one pipeline.</b> The per base part - recall, in base fusion, fact source
 * repair - runs once per selected knowledge base and produces an in base ranking; everything after it
 * runs once on the merged candidate set. A single base call is literally the multi base call with one
 * base, which is what keeps the management console's debug page and an application's production traffic
 * on the same code (requirement sections 4.7 and 4.9).
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
    private static final String META_CHILDREN = RetrievalMetadataKeys.CHILDREN;
    private static final String META_CHILD_CHUNK_ID = "chunk_id";
    private static final String META_CHILD_CONTENT = RetrievalMetadataKeys.CHILD_CONTENT;
    private static final String META_CHILD_SCORE = "score";
    private static final String META_CHILD_SCORE_TYPE = "score_type";

    /**
     * Knowledge base a node came from. Present on every node, single base calls included: a caller that
     * has to branch on whether the key exists cannot use it, and the value is a fact of the chunk row
     * rather than a property of the routing stage.
     */
    private static final String META_KB_ID = "kb_id";

    private static final int ENABLED = 1;

    /** Index of the first declared knowledge base, whose settings complete an unset parameter. */
    private static final int PRIMARY = 0;

    private final KnowledgeBaseService knowledgeBaseService;
    private final DocumentMapper documentMapper;
    private final ChunkMapper chunkMapper;
    private final FulltextStore fulltextStore;
    private final VectorStore vectorStore;
    private final EmbeddingProvider embeddingProvider;
    private final IndexAliasManager indexAliasManager;
    private final FusionRouter fusionRouter;
    private final CrossKbRrfFusion crossKbRrfFusion;
    private final KbQuotaAllocator kbQuotaAllocator;
    private final RoutingService routingService;
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
     * Runs one retrieval call against a single knowledge base.
     *
     * <p>What the management console's debug page and the evaluation runner call: both work on one base by
     * definition, the first because an operator tunes one base at a time, the second because an evaluation
     * data set belongs to one base (requirement section 4.6).
     *
     * @param kbId    knowledge base business id
     * @param command request parameters, every tuning field optional
     * @return ordered nodes, degradation markers and the applied parameter summary
     */
    public SearchOutcome search(String kbId, RetrievalCommand command) {
        return search(List.of(KbRef.of(kbId)), command);
    }

    /**
     * Runs one retrieval call across the knowledge bases an application links, requirement sections 4.7
     * and 4.9.
     *
     * @param kbRefs  linked knowledge bases with their quota weights, in declaration order
     * @param command request parameters, every tuning field optional
     * @return ordered nodes, degradation markers and the applied parameter summary
     */
    public SearchOutcome search(List<KbRef> kbRefs, RetrievalCommand command) {
        // The single guard of the whole pipeline: every stage below may assume at least one base, and a
        // second check further down would only be able to report the same fact later and less clearly.
        if (CollectionUtils.isEmpty(kbRefs)) {
            throw BizException.invalidParam("检索必须指定至少一个知识库");
        }
        List<KbTarget> targets = loadTargets(kbRefs);
        // Layer three of the configuration model is a knowledge base setting while the parameters it feeds
        // are call level (one fusion strategy, one top_n for the whole response). The first declared base
        // supplies them, so the winner is what the operator declared first rather than an iteration order.
        KbTarget primary = targets.get(PRIMARY);
        RetrievalSettings settings = RetrievalSettings.resolve(command, primary.retrievalConfig(),
                properties.getRetrieval());

        List<String> degraded = new ArrayList<>();
        RewriteOutcome rewrite = rewriteService.rewrite(command.getQuery(), command.getMessages(),
                shouldRun(settings.isRewriteEnabled(), rewriteService.isAvailable(),
                        command.getRewriteEnabled(), primary.rewriteEnabled()));
        addMarker(degraded, rewrite.getDegradedReason());
        String effectiveQuery = rewrite.getQuery();

        RoutingOutcome routing = routingService.route(knowledgeBasesOf(targets), effectiveQuery,
                Boolean.TRUE.equals(command.getRoutingEnabled()), command.getRoutingPrompt());
        addMarker(degraded, routing.getDegradedReason());
        List<KbTarget> selected = retain(targets, routing.getKbIds());

        // Both routes default to enabled; only the evaluation runner ever sets one of them to false, to
        // realise a single route configuration of its matrix even once every model is configured.
        boolean bm25RouteRequested = !Boolean.FALSE.equals(command.getBm25RouteEnabled());
        boolean vectorRouteRequested = !Boolean.FALSE.equals(command.getVectorRouteEnabled());
        boolean vectorRouteRan = vectorRouteRequested && embeddingProvider.isConfigured();

        Map<String, List<String>> visibleByKb = visibleVersionIds(selected);
        if (visibleByKb.isEmpty()) {
            // Not one selected base holds an active version, so no route ever ran and none of them can be
            // reported as degraded.
            recordDegradation(!degraded.isEmpty());
            return new SearchOutcome(List.of(), degraded, applied(effectiveQuery,
                    settings.getFusion().getMode(), ThresholdTarget.NONE.code(), routing.getKbIds()));
        }
        if (!vectorRouteRan && vectorRouteRequested) {
            // A route that was never asked to run is not a degradation - only report the marker when the
            // vector route was wanted (implicitly or explicitly) and could not run.
            addMarker(degraded, DegradedReason.VECTOR_ROUTE_UNAVAILABLE.code());
        }
        // One embedding for the whole call: every base is asked the same question and the routes differ
        // only in the index they hit, so embedding once per base would pay N times for one vector.
        float[] queryVector = vectorRouteRan
                ? embeddingProvider.embed(List.of(effectiveQuery)).get(0) : null;

        Map<String, List<FusedChunk>> rankedByKb = new LinkedHashMap<>();
        Map<String, Chunk> chunkById = new HashMap<>();
        for (KbTarget target : selected) {
            List<String> visibleVersionIds = visibleByKb.get(target.kbId());
            if (visibleVersionIds == null) {
                continue;
            }
            KbRecall recall = recallWithinKb(target, effectiveQuery, queryVector, visibleVersionIds,
                    settings, command, bm25RouteRequested);
            chunkById.putAll(recall.chunkById());
            if (!recall.ranked().isEmpty()) {
                rankedByKb.put(target.kbId(), recall.ranked());
            }
        }

        List<FusedChunk> merged = mergeAcrossKbs(rankedByKb, kbRefs, settings);
        // Cross base merging is reciprocal rank fusion whatever the in base strategy was, so the score the
        // ranking is built on - and therefore the score type a node reports - changes with the base count.
        FusionMode orderingMode = rankedByKb.size() > 1 ? FusionMode.RRF : settings.getFusion().getMode();
        boolean parentChildEnabled = selected.stream()
                .anyMatch(target -> target.indexConfig().parentChildEnabled());

        List<RetrievalCandidate> candidates = selectCandidates(merged, chunkById, parentChildEnabled, settings);
        applyRerank(effectiveQuery, candidates, settings, command, primary.retrievalConfig(), degraded);

        candidates.sort(Comparator.comparingDouble(RetrievalCandidate::orderingScore).reversed()
                .thenComparing(RetrievalCandidate::chunkId));
        List<RetrievalUnit> units = parentChildMerger.merge(candidates);
        Map<String, KbTarget> targetByKbId = byKbId(selected);
        DisabledChildVisibility.Visibility visibility = disabledChildVisibility.apply(units,
                disabledChildVisibility.disabledChildrenOf(parentIdsOf(units)),
                unit -> hidesParentWithDisabledChild(unit, targetByKbId));
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

        List<RetrievalNodeView> nodes = toNodes(units, decision, orderingMode,
                visibility.disabledChildIdsByUnit());
        log.info("search finished, routedKbIds={}, recallTopK={}, topN={}, fusion={}, candidates={}, "
                        + "units={}, returned={}, rerank={}, degraded={}",
                routing.getKbIds(), settings.getRecallTopK(), settings.getTopN(), orderingMode.code(),
                candidates.size(), units.size(), nodes.size(), rerankApplied, degraded);
        recordDegradation(!degraded.isEmpty());
        return new SearchOutcome(nodes, degraded,
                applied(effectiveQuery, orderingMode, decision.appliedOn(), routing.getKbIds()));
    }

    /**
     * Loads the knowledge base and its two configuration blocks for every linked base.
     *
     * @param kbRefs linked knowledge bases in declaration order
     * @return targets in declaration order
     */
    private List<KbTarget> loadTargets(List<KbRef> kbRefs) {
        List<KbTarget> targets = new ArrayList<>(kbRefs.size());
        for (KbRef ref : kbRefs) {
            KnowledgeBase knowledgeBase = knowledgeBaseService.require(ref.kbId());
            targets.add(new KbTarget(knowledgeBase,
                    knowledgeBaseService.indexConfigOf(knowledgeBase),
                    JsonUtil.parse(knowledgeBase.getRetrievalConfig(), KbRetrievalConfig.class)));
        }
        return targets;
    }

    /**
     * Runs the per base part of the pipeline: both recall routes, the in base fusion and the fact source
     * repair pass.
     *
     * <p>This is the whole of what a knowledge base contributes on its own. It stops at the in base
     * ranking on purpose: the next stage compares bases, and a stage that had already reranked or
     * thresholded inside one base would be comparing numbers produced by different corpora.
     *
     * @param target             knowledge base being searched
     * @param query              query the routes run with, already rewritten
     * @param queryVector        embedded query, {@code null} when the vector route does not run
     * @param visibleVersionIds  version visibility set of this base
     * @param settings           effective retrieval parameters of the call
     * @param command            request parameters, read for the metadata filter
     * @param bm25RouteRequested {@code true} when the BM25 route runs
     * @return in base ranking of the live candidates plus their fact source rows
     */
    private KbRecall recallWithinKb(KbTarget target, String query, float[] queryVector,
                                    List<String> visibleVersionIds, RetrievalSettings settings,
                                    RetrievalCommand command, boolean bm25RouteRequested) {
        String kbId = target.kbId();
        RetrievalFilter filter = RetrievalFilter.builder()
                .kbId(kbId)
                .documentVersionIds(visibleVersionIds)
                .enabledOnly(true)
                .metadataFilter(command.getMetadataFilter())
                .build();
        Map<RetrievalSource, List<ScoredChunk>> routeResults = recall(kbId, query, queryVector, filter,
                settings.getRecallTopK(), bm25RouteRequested);
        List<FusedChunk> fused = fusionRouter.fuse(routeResults, settings.getFusion());
        Map<String, Chunk> chunkById = loadChunks(fused);
        List<FusedChunk> live = dropDisabled(dropOrphans(kbId, fused, chunkById), chunkById);
        return new KbRecall(live, chunkById);
    }

    /**
     * Merges the per base rankings into the candidate set the rerank stage sees, requirement section 4.7
     * "quota total".
     *
     * <p>A single contributing base short circuits to its own ranking: reciprocal rank fusion of one list
     * would only relabel it, and relabelling would replace the in base fusion score - the very number the
     * single base contract reports - with a rank derived one.
     *
     * <p>The quota is divided between the bases that actually returned candidates rather than between
     * every routed base, so a base whose corpus had nothing to say does not reserve budget the others
     * could use.
     *
     * @param rankedByKb in base ranking per contributing knowledge base id
     * @param kbRefs     linked knowledge bases with their weights, in declaration order
     * @param settings   effective retrieval parameters of the call
     * @return unified candidate order
     */
    private List<FusedChunk> mergeAcrossKbs(Map<String, List<FusedChunk>> rankedByKb, List<KbRef> kbRefs,
                                            RetrievalSettings settings) {
        if (rankedByKb.isEmpty()) {
            return List.of();
        }
        if (rankedByKb.size() == 1) {
            return rankedByKb.values().iterator().next();
        }
        Map<String, Integer> quotas = kbQuotaAllocator.allocate(
                contributingRefs(kbRefs, rankedByKb.keySet()), rerankService.candidateLimit());
        Map<String, List<FusedChunk>> withinQuota = new LinkedHashMap<>(rankedByKb.size());
        for (Map.Entry<String, List<FusedChunk>> entry : rankedByKb.entrySet()) {
            int quota = quotas.getOrDefault(entry.getKey(), 0);
            List<FusedChunk> ranking = entry.getValue();
            withinQuota.put(entry.getKey(),
                    ranking.size() <= quota ? ranking : ranking.subList(0, quota));
        }
        List<FusedChunk> merged = crossKbRrfFusion.merge(withinQuota, settings.getFusion().getRrfK());
        log.info("cross knowledge base merge finished, quotas={}, merged={}", quotas, merged.size());
        return merged;
    }

    /**
     * Weighted references of the bases that contributed candidates, in declaration order.
     *
     * @param kbRefs          linked knowledge bases in declaration order
     * @param contributingIds ids that returned at least one candidate
     * @return references to allocate the quota between
     */
    private List<KbRef> contributingRefs(List<KbRef> kbRefs, Set<String> contributingIds) {
        List<KbRef> refs = new ArrayList<>(contributingIds.size());
        for (KbRef ref : kbRefs) {
            if (contributingIds.contains(ref.kbId())) {
                refs.add(ref);
            }
        }
        return refs;
    }

    /**
     * Keeps the targets the routing stage selected, in declaration order.
     *
     * @param targets every linked target
     * @param kbIds   selected knowledge base ids
     * @return selected targets
     */
    private List<KbTarget> retain(List<KbTarget> targets, List<String> kbIds) {
        if (targets.size() == kbIds.size()) {
            return targets;
        }
        return targets.stream().filter(target -> kbIds.contains(target.kbId())).toList();
    }

    private Map<String, KbTarget> byKbId(List<KbTarget> targets) {
        Map<String, KbTarget> byKbId = new HashMap<>(targets.size());
        for (KbTarget target : targets) {
            byKbId.put(target.kbId(), target);
        }
        return byKbId;
    }

    private List<KnowledgeBase> knowledgeBasesOf(List<KbTarget> targets) {
        return targets.stream().map(KbTarget::knowledgeBase).toList();
    }

    /**
     * Tells whether the knowledge base a unit came from hides parents containing disabled children.
     *
     * @param unit         merged unit
     * @param targetByKbId selected targets by knowledge base id
     * @return {@code true} when that base's switch is on
     */
    private boolean hidesParentWithDisabledChild(RetrievalUnit unit, Map<String, KbTarget> targetByKbId) {
        KbTarget target = targetByKbId.get(unit.best().getChunk().getKbId());
        return target != null && target.indexConfig().isHideParentWithDisabledChild();
    }

    /**
     * One knowledge base of a call with the two configuration blocks the pipeline reads from it.
     *
     * @param knowledgeBase   knowledge base row, carrying the name and description the routing prompt uses
     * @param indexConfig     index configuration, read for the split mode and the visibility switch
     * @param retrievalConfig knowledge base level retrieval defaults, {@code null} when never configured
     */
    private record KbTarget(KnowledgeBase knowledgeBase, KbIndexConfig indexConfig,
                            KbRetrievalConfig retrievalConfig) {

        String kbId() {
            return knowledgeBase.getKbId();
        }

        Boolean rewriteEnabled() {
            return retrievalConfig == null ? null : retrievalConfig.getRewriteEnabled();
        }
    }

    /**
     * What one knowledge base contributed to a call.
     *
     * @param ranked    in base ranking of the candidates a fact source row still backs
     * @param chunkById fact source rows of this base's candidates
     */
    private record KbRecall(List<FusedChunk> ranked, Map<String, Chunk> chunkById) {
    }

    /**
     * Feeds the production degradation monitor, unless this call is an offline evaluation run.
     *
     * <p>An evaluation batch can carry hundreds of calls in the same observation window the production
     * alert reads; letting them in would let a batch of judgments trip an alert meant for traffic
     * nobody actually served.
     *
     * @param degraded {@code true} when this call carried at least one degradation marker
     */
    private void recordDegradation(boolean degraded) {
        if (!OfflineExecutionContext.isOffline()) {
            degradeMonitor.record(degraded);
        }
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

    /**
     * Issues the recall routes against one knowledge base.
     *
     * @param kbId               knowledge base business id
     * @param query              query text of the BM25 route
     * @param queryVector        embedded query, {@code null} switches the vector route off
     * @param filter             mandatory engine side predicate
     * @param recallTopK         candidates requested per route
     * @param bm25RouteRequested {@code true} when the BM25 route runs
     * @return candidates per route
     */
    private Map<RetrievalSource, List<ScoredChunk>> recall(String kbId, String query, float[] queryVector,
                                                           RetrievalFilter filter, int recallTopK,
                                                           boolean bm25RouteRequested) {
        Map<RetrievalSource, List<ScoredChunk>> routeResults = new EnumMap<>(RetrievalSource.class);
        if (bm25RouteRequested) {
            routeResults.put(RetrievalSource.BM25, fulltextStore.searchBm25(
                    indexAliasManager.fulltextAlias(kbId),
                    FulltextQuery.builder().queryText(query).topK(recallTopK).filter(filter).build()));
        }
        if (queryVector != null) {
            routeResults.put(RetrievalSource.VECTOR, vectorStore.search(
                    indexAliasManager.vectorAlias(kbId),
                    VectorQuery.builder().queryVector(queryVector).topK(recallTopK).filter(filter).build()));
        }
        return routeResults;
    }

    /**
     * Cuts the fused list down to the candidates worth carrying into the rerank and merge stages.
     *
     * @param live               fused candidates that still exist in MySQL
     * @param chunkById          fact source rows
     * @param parentChildEnabled {@code true} when at least one searched base splits into two levels
     * @param settings           effective retrieval parameters
     * @return mutable candidate list in fusion order
     */
    private List<RetrievalCandidate> selectCandidates(List<FusedChunk> live, Map<String, Chunk> chunkById,
                                                      boolean parentChildEnabled, RetrievalSettings settings) {
        int maxCandidates = Math.min(rerankService.candidateLimit(), live.size());
        int count = maxCandidates;
        if (parentChildEnabled) {
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
     * Collects the version visibility set of every selected knowledge base.
     *
     * <p>Resolved before any engine call, and that ordering is the point: a base holding no active version
     * cannot recall anything, so knowing it up front is what keeps the embedding call from being paid for
     * a search that has no index to hit.
     *
     * @param targets selected knowledge bases
     * @return visibility set per knowledge base id, bases without any active version left out
     */
    private Map<String, List<String>> visibleVersionIds(List<KbTarget> targets) {
        Map<String, List<String>> visibleByKb = new LinkedHashMap<>(targets.size());
        for (KbTarget target : targets) {
            List<String> visible = visibleVersionIds(target.kbId());
            if (CollectionUtils.isEmpty(visible)) {
                log.info("no active document version, kbId={}", target.kbId());
                continue;
            }
            visibleByKb.put(target.kbId(), visible);
        }
        return visibleByKb;
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

    /**
     * Turns the surviving units into the transport neutral nodes.
     *
     * @param orderingMode           strategy that produced the final ordering: the in base one on a single
     *                               base call, reciprocal rank fusion once bases were merged
     * @param units                  surviving units in ranking order
     * @param decision               threshold decision of this search
     * @param disabledChildIdsByUnit disabled child ids to report per unit
     * @return ordered nodes
     */
    private List<RetrievalNodeView> toNodes(List<RetrievalUnit> units,
                                            ScoreThresholdPolicy.ThresholdDecision decision,
                                            FusionMode orderingMode,
                                            Map<String, List<String>> disabledChildIdsByUnit) {
        if (CollectionUtils.isEmpty(units)) {
            return List.of();
        }
        Map<String, Chunk> parentById = loadParents(units);
        List<RetrievalNodeView> nodes = new ArrayList<>(units.size());
        for (RetrievalUnit unit : units) {
            RetrievalCandidate best = unit.best();
            ScoreThresholdPolicy.ReportedScore reported =
                    scoreThresholdPolicy.report(best, decision, orderingMode);
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
                    .metadata(buildMetadata(unit, answerChunk, decision, orderingMode,
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
                                              FusionMode orderingMode,
                                              List<String> disabledChildIds) {
        Map<String, Object> metadata = new LinkedHashMap<>(scoreMetadata(unit.best()));
        // Read from the fact source row rather than from the routing decision: a node has to be traceable
        // to its base even when routing never ran, and the row is the only place that cannot disagree.
        metadata.put(META_KB_ID, answerChunk.getKbId());
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
                    scoreThresholdPolicy.report(member, decision, orderingMode);
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

    /**
     * Builds the applied parameter summary.
     *
     * @param usedQuery          query the recall stage ran with
     * @param orderingMode       strategy that produced the final ordering, which on a merged call is the
     *                           cross base one rather than the configured in base strategy
     * @param thresholdAppliedOn score the threshold acted on
     * @param routedKbIds        bases this call searched
     * @return applied summary
     */
    private AppliedInfo applied(String usedQuery, FusionMode orderingMode, String thresholdAppliedOn,
                                List<String> routedKbIds) {
        return AppliedInfo.builder()
                .rewriteUsedQuery(usedQuery)
                .fusionMode(orderingMode.code())
                .thresholdAppliedOn(thresholdAppliedOn)
                .routedKbIds(routedKbIds)
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
