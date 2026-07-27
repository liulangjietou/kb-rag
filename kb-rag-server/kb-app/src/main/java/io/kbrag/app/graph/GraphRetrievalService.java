package io.kbrag.app.graph;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.enums.DegradedReason;
import io.kbrag.domain.enums.RetrievalSource;
import io.kbrag.domain.mapper.ChunkMapper;
import io.kbrag.domain.model.GraphChunkRelevance;
import io.kbrag.domain.model.GraphTraceRow;
import io.kbrag.domain.model.GraphTraversalQuery;
import io.kbrag.domain.model.RetrievalFilter;
import io.kbrag.domain.model.ScoredChunk;
import io.kbrag.domain.port.GraphStore;
import io.kbrag.domain.service.GraphQueryTokenizer;
import io.kbrag.domain.service.GraphRelevanceScorer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The graph route of one knowledge base, requirement section 4.9.
 *
 * <p><b>Zero model calls.</b> The query is tokenised and matched against the entity name index; the
 * entities it hits are expanded along relations, and the chunks the reached entities were extracted from
 * are what the route recalls. No entity extraction runs on the query, so the route costs one graph round
 * trip and can sit on the critical path of every search.
 *
 * <p><b>The graph proposes, MySQL disposes.</b> Every chunk the traversal returns is re-checked against
 * the very same predicate the other two routes were filtered by - the version visibility set and the
 * enabled flag - by reading the fact source rows. This is not belt and braces: the graph is written when
 * an extraction runs and is not rewritten when an operator disables a chunk or switches a document
 * version, so its properties are known to lag. Trusting them would let the graph route return a passage
 * the other two routes are forbidden to return, which is the version isolation being broken from the
 * side (requirement section 4.9, "the recalled chunk is subject to the same forced filter").
 *
 * <p><b>Failures degrade, they never fail the search.</b> A knowledge base with the route switched on and
 * no reachable graph reports {@code graph_route_unavailable} and lets the other two routes answer. The
 * snapshot case is deliberately different and carries no marker: a released version searches a frozen
 * corpus, the graph only ever holds the active one, so switching the route off there is the contract
 * being honoured rather than a fault - see {@link #recall}.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GraphRetrievalService {

    private static final int ENABLED = 1;

    private final GraphStore graphStore;
    private final ChunkMapper chunkMapper;
    private final GraphQueryTokenizer tokenizer;
    private final GraphRelevanceScorer relevanceScorer;
    private final KbProperties properties;

    /**
     * Tells whether the deployment can run the route at all.
     *
     * @return {@code true} when a graph is configured
     */
    public boolean isAvailable() {
        return graphStore.isEnabled();
    }

    /**
     * Runs the graph route against one knowledge base.
     *
     * <p>The caller decides whether the route is wanted; this method decides whether it can run. That
     * split is what keeps the snapshot rule out of here: the retrieval pipeline never asks for the route
     * on a snapshot bound context, so this method never has to know what a snapshot is.
     *
     * @param query      query the other routes ran with, already rewritten
     * @param filter     the very predicate the engine side routes were filtered by
     * @param recallTopK candidates the route contributes at most
     * @return candidates, relevance evidence and an optional degradation marker
     */
    public GraphRouteOutcome recall(String query, RetrievalFilter filter, int recallTopK) {
        if (!graphStore.isEnabled()) {
            log.info("graph route requested without a configured graph, kbId={}", filter.getKbId());
            return GraphRouteOutcome.degraded(DegradedReason.GRAPH_ROUTE_UNAVAILABLE.code());
        }
        List<String> terms = tokenizer.tokenize(query);
        if (CollectionUtils.isEmpty(terms)) {
            return GraphRouteOutcome.skipped();
        }
        KbProperties.Graph config = properties.getGraph();
        List<GraphTraceRow> rows;
        try {
            rows = graphStore.traverse(GraphTraversalQuery.builder()
                    .kbId(filter.getKbId())
                    .terms(terms)
                    .entityMatchLimit(config.getEntityMatchLimit())
                    .maxHops(config.getMaxHops())
                    .chunkLimit(recallTopK)
                    .build());
        } catch (Exception e) {
            // An unreachable graph is a degradation of one route, never a failed search: the other two
            // routes hold the corpus the graph only points into.
            log.error("graph traversal failed, errorCode={}, kbId={}",
                    ErrorCode.INTERNAL_ERROR, filter.getKbId(), e);
            return GraphRouteOutcome.degraded(DegradedReason.GRAPH_ROUTE_UNAVAILABLE.code());
        }
        List<GraphChunkRelevance> ranked = relevanceScorer.rank(rows, recallTopK);
        if (CollectionUtils.isEmpty(ranked)) {
            return GraphRouteOutcome.skipped();
        }
        Set<String> admitted = admittedChunkIds(filter,
                ranked.stream().map(GraphChunkRelevance::chunkId).toList());

        List<ScoredChunk> candidates = new ArrayList<>(ranked.size());
        Map<String, GraphChunkRelevance> evidence = new LinkedHashMap<>(ranked.size());
        for (GraphChunkRelevance relevance : ranked) {
            if (!admitted.contains(relevance.chunkId())) {
                continue;
            }
            candidates.add(new ScoredChunk(relevance.chunkId(), relevance.score(), RetrievalSource.GRAPH));
            evidence.put(relevance.chunkId(), relevance);
        }
        log.info("graph route finished, kbId={}, terms={}, traced={}, ranked={}, admitted={}",
                filter.getKbId(), terms.size(), rows.size(), ranked.size(), candidates.size());
        return candidates.isEmpty() ? GraphRouteOutcome.skipped()
                : GraphRouteOutcome.of(candidates, evidence);
    }

    /**
     * Re-applies the mandatory retrieval predicate to the chunks the graph proposed, on the fact source.
     *
     * <p>The predicate is read off the {@link RetrievalFilter} the engine routes were built with rather
     * than rebuilt from the knowledge base, so the two can never diverge: if the call is scoped to a
     * frozen version set the graph route is scoped to the same set, and a chunk absent from MySQL simply
     * fails to be admitted.
     *
     * @param filter   predicate of this call
     * @param chunkIds chunks the traversal proposed
     * @return chunk ids that may take part in the ranking
     */
    private Set<String> admittedChunkIds(RetrievalFilter filter, List<String> chunkIds) {
        LambdaQueryWrapper<Chunk> wrapper = new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getKbId, filter.getKbId())
                .in(Chunk::getChunkId, chunkIds);
        if (CollectionUtils.isNotEmpty(filter.getDocumentVersionIds())) {
            wrapper.in(Chunk::getDocumentVersionId, filter.getDocumentVersionIds());
        }
        if (filter.isEnabledOnly()) {
            wrapper.eq(Chunk::getEnabled, ENABLED);
        }
        return chunkMapper.selectList(wrapper).stream().map(Chunk::getChunkId).collect(Collectors.toSet());
    }
}
