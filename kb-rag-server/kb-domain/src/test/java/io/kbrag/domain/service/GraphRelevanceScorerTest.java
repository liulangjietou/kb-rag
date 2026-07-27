package io.kbrag.domain.service;

import io.kbrag.domain.model.GraphChunkRelevance;
import io.kbrag.domain.model.GraphTraceRow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the relevance formula of the graph route: the hop reciprocal, the maximum over the entities that
 * reached the same chunk, the reported entity cap and the deterministic ordering.
 *
 * @author owlzhangfq@gmail.com
 */
class GraphRelevanceScorerTest {

    private static final double DELTA = 1e-9d;
    private static final int LIMIT = 10;

    private final GraphRelevanceScorer scorer = new GraphRelevanceScorer();

    @Test
    void shouldHalveAMatchScoreOfOneHop() {
        List<GraphChunkRelevance> ranked = scorer.rank(
                List.of(new GraphTraceRow("ck_1", "苹果公司", 0.8d, 1)), LIMIT);

        assertEquals(1, ranked.size());
        assertEquals(0.4d, ranked.get(0).score(), DELTA);
        assertEquals(1, ranked.get(0).hops());
    }

    @Test
    void shouldKeepTheWholeMatchScoreOfADirectMention() {
        List<GraphChunkRelevance> ranked = scorer.rank(
                List.of(new GraphTraceRow("ck_1", "苹果公司", 0.8d, 0)), LIMIT);

        assertEquals(0.8d, ranked.get(0).score(), DELTA);
        assertEquals(0, ranked.get(0).hops());
    }

    @Test
    void shouldDivideByThreeAtTwoHops() {
        List<GraphChunkRelevance> ranked = scorer.rank(
                List.of(new GraphTraceRow("ck_1", "seed", 0.9d, 2)), LIMIT);

        assertEquals(0.3d, ranked.get(0).score(), DELTA);
    }

    @Test
    void shouldTakeTheMaximumWhenSeveralEntitiesReachTheSameChunk() {
        List<GraphChunkRelevance> ranked = scorer.rank(List.of(
                new GraphTraceRow("ck_1", "far", 0.6d, 2),
                new GraphTraceRow("ck_1", "near", 0.8d, 0),
                new GraphTraceRow("ck_1", "middle", 0.6d, 1)), LIMIT);

        // Summing would make a chunk win by naming many things; the requirement asks for the maximum.
        // 0.8/1 = 0.8 beats 0.6/2 = 0.3 and 0.6/3 = 0.2, and the reported names follow the same order.
        assertEquals(1, ranked.size());
        assertEquals(0.8d, ranked.get(0).score(), DELTA);
        assertEquals(0, ranked.get(0).hops());
        assertEquals(List.of("near", "middle", "far"), ranked.get(0).entityNames());
    }

    @Test
    void shouldKeepOnlyTheShortestPathOfTheSameEntity() {
        List<GraphChunkRelevance> ranked = scorer.rank(List.of(
                new GraphTraceRow("ck_1", "seed", 0.6d, 2),
                new GraphTraceRow("ck_1", "seed", 0.6d, 1)), LIMIT);

        assertEquals(List.of("seed"), ranked.get(0).entityNames());
        assertEquals(0.3d, ranked.get(0).score(), DELTA);
        assertEquals(1, ranked.get(0).hops());
    }

    @Test
    void shouldReportAtMostFiveEntityNames() {
        List<GraphTraceRow> rows = List.of(
                new GraphTraceRow("ck_1", "e1", 0.9d, 0),
                new GraphTraceRow("ck_1", "e2", 0.8d, 0),
                new GraphTraceRow("ck_1", "e3", 0.7d, 0),
                new GraphTraceRow("ck_1", "e4", 0.6d, 0),
                new GraphTraceRow("ck_1", "e5", 0.5d, 0),
                new GraphTraceRow("ck_1", "e6", 0.4d, 0));

        List<String> names = scorer.rank(rows, LIMIT).get(0).entityNames();

        assertEquals(GraphRelevanceScorer.MAX_REPORTED_ENTITIES, names.size());
        assertEquals(List.of("e1", "e2", "e3", "e4", "e5"), names);
    }

    @Test
    void shouldOrderByRelevanceAndBreakTiesOnTheChunkId() {
        List<GraphChunkRelevance> ranked = scorer.rank(List.of(
                new GraphTraceRow("ck_b", "seed", 0.5d, 0),
                new GraphTraceRow("ck_a", "seed", 0.5d, 0),
                new GraphTraceRow("ck_c", "seed", 0.9d, 0)), LIMIT);

        assertEquals(List.of("ck_c", "ck_a", "ck_b"),
                ranked.stream().map(GraphChunkRelevance::chunkId).toList());
    }

    @Test
    void shouldTruncateToTheRequestedLimit() {
        List<GraphChunkRelevance> ranked = scorer.rank(List.of(
                new GraphTraceRow("ck_1", "seed", 0.9d, 0),
                new GraphTraceRow("ck_2", "seed", 0.8d, 0),
                new GraphTraceRow("ck_3", "seed", 0.7d, 0)), 2);

        assertEquals(List.of("ck_1", "ck_2"),
                ranked.stream().map(GraphChunkRelevance::chunkId).toList());
    }

    @Test
    void shouldReturnNothingForNoPathAtAll() {
        assertTrue(scorer.rank(List.of(), LIMIT).isEmpty());
        assertTrue(scorer.rank(null, LIMIT).isEmpty());
        assertTrue(scorer.rank(List.of(new GraphTraceRow("ck_1", "seed", 1.0d, 0)), 0).isEmpty());
    }
}
