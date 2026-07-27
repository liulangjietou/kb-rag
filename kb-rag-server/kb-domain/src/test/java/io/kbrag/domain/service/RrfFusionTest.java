package io.kbrag.domain.service;

import io.kbrag.domain.enums.FusionMode;
import io.kbrag.domain.enums.RetrievalSource;
import io.kbrag.domain.model.FusedChunk;
import io.kbrag.domain.model.FusionParams;
import io.kbrag.domain.model.ScoredChunk;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers reciprocal rank fusion: the arithmetic, the ordering and the per route bookkeeping the API
 * relies on to report an honest score type.
 *
 * @author owlzhangfq@gmail.com
 */
class RrfFusionTest {

    private static final double TOLERANCE = 1e-9;

    private final RrfFusion fusion = new RrfFusion();

    @Test
    void shouldReturnEmptyListWithoutRoutes() {
        assertTrue(fusion.fuse(Map.of(), params(RrfFusion.DEFAULT_K)).isEmpty());
    }

    @Test
    void shouldComputeReciprocalRankSum() {
        Map<RetrievalSource, List<ScoredChunk>> routes = new EnumMap<>(RetrievalSource.class);
        routes.put(RetrievalSource.VECTOR, List.of(
                new ScoredChunk("a", 0.9d, RetrievalSource.VECTOR),
                new ScoredChunk("b", 0.8d, RetrievalSource.VECTOR)));
        routes.put(RetrievalSource.BM25, List.of(
                new ScoredChunk("b", 12.0d, RetrievalSource.BM25),
                new ScoredChunk("c", 9.0d, RetrievalSource.BM25)));

        List<FusedChunk> fused = fusion.fuse(routes, params(60));

        Map<String, FusedChunk> byId = new java.util.HashMap<>();
        fused.forEach(chunk -> byId.put(chunk.getChunkId(), chunk));
        // "b" appears second in the vector route and first in the BM25 route.
        assertEquals(1.0d / 62 + 1.0d / 61, byId.get("b").getFusedScore(), TOLERANCE);
        assertEquals(1.0d / 61, byId.get("a").getFusedScore(), TOLERANCE);
        assertEquals(1.0d / 62, byId.get("c").getFusedScore(), TOLERANCE);
    }

    @Test
    void shouldRankMultiRouteHitsFirst() {
        Map<RetrievalSource, List<ScoredChunk>> routes = new EnumMap<>(RetrievalSource.class);
        routes.put(RetrievalSource.VECTOR, List.of(
                new ScoredChunk("a", 0.9d, RetrievalSource.VECTOR),
                new ScoredChunk("shared", 0.5d, RetrievalSource.VECTOR)));
        routes.put(RetrievalSource.BM25, List.of(
                new ScoredChunk("shared", 4.0d, RetrievalSource.BM25),
                new ScoredChunk("c", 1.0d, RetrievalSource.BM25)));

        List<FusedChunk> fused = fusion.fuse(routes, params(60));

        assertEquals("shared", fused.get(0).getChunkId());
    }

    @Test
    void shouldPreserveOrderOfASingleRoute() {
        Map<RetrievalSource, List<ScoredChunk>> routes = new EnumMap<>(RetrievalSource.class);
        routes.put(RetrievalSource.BM25, List.of(
                new ScoredChunk("first", 10.0d, RetrievalSource.BM25),
                new ScoredChunk("second", 5.0d, RetrievalSource.BM25),
                new ScoredChunk("third", 1.0d, RetrievalSource.BM25)));

        List<FusedChunk> fused = fusion.fuse(routes, params(RrfFusion.DEFAULT_K));

        assertEquals(List.of("first", "second", "third"),
                fused.stream().map(FusedChunk::getChunkId).toList());
        assertEquals(RetrievalSource.BM25, fused.get(0).getPrimarySource());
    }

    @Test
    void shouldRecordRanksAndScoresPerRoute() {
        Map<RetrievalSource, List<ScoredChunk>> routes = new EnumMap<>(RetrievalSource.class);
        routes.put(RetrievalSource.VECTOR, List.of(new ScoredChunk("a", 0.77d, RetrievalSource.VECTOR)));
        routes.put(RetrievalSource.BM25, List.of(
                new ScoredChunk("z", 8.0d, RetrievalSource.BM25),
                new ScoredChunk("a", 3.0d, RetrievalSource.BM25)));

        FusedChunk candidate = fusion.fuse(routes, params(60)).stream()
                .filter(chunk -> "a".equals(chunk.getChunkId()))
                .findFirst()
                .orElseThrow();

        assertEquals(1, candidate.getRouteRanks().get(RetrievalSource.VECTOR));
        assertEquals(2, candidate.getRouteRanks().get(RetrievalSource.BM25));
        assertEquals(0.77d, candidate.getRouteScores().get(RetrievalSource.VECTOR), TOLERANCE);
        // The vector route ranked it best, so that is the score the API reports.
        assertEquals(RetrievalSource.VECTOR, candidate.getPrimarySource());
    }

    @Test
    void shouldBeDeterministicOnScoreTies() {
        Map<RetrievalSource, List<ScoredChunk>> routes = new EnumMap<>(RetrievalSource.class);
        routes.put(RetrievalSource.BM25, List.of(
                new ScoredChunk("b", 1.0d, RetrievalSource.BM25)));
        routes.put(RetrievalSource.VECTOR, List.of(
                new ScoredChunk("a", 1.0d, RetrievalSource.VECTOR)));

        List<FusedChunk> fused = fusion.fuse(routes, params(60));

        assertEquals(List.of("a", "b"), fused.stream().map(FusedChunk::getChunkId).toList());
    }

    @Test
    void shouldRejectNonPositiveK() {
        assertThrows(IllegalArgumentException.class, () -> params(0));
    }

    private FusionParams params(int k) {
        return FusionParams.of(FusionMode.RRF, k, FusionParams.DEFAULT_W_VECTOR);
    }
}
