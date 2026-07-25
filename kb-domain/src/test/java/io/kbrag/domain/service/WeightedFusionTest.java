package io.kbrag.domain.service;

import io.kbrag.domain.enums.FusionMode;
import io.kbrag.domain.enums.RetrievalSource;
import io.kbrag.domain.model.FusedChunk;
import io.kbrag.domain.model.FusionParams;
import io.kbrag.domain.model.ScoredChunk;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers weighted fusion: the min-max normalisation, the weighting, and the two degenerate inputs
 * whose handling decides whether a route silently disappears from the ranking.
 *
 * @author owlzhangfq@gmail.com
 */
class WeightedFusionTest {

    private static final double TOLERANCE = 1e-9;

    private final WeightedFusion fusion = new WeightedFusion();

    @Test
    void shouldNormaliseEachRouteIntoTheUnitInterval() {
        Map<RetrievalSource, List<ScoredChunk>> routes = new EnumMap<>(RetrievalSource.class);
        routes.put(RetrievalSource.VECTOR, List.of(
                new ScoredChunk("a", 0.9d, RetrievalSource.VECTOR),
                new ScoredChunk("b", 0.7d, RetrievalSource.VECTOR),
                new ScoredChunk("c", 0.5d, RetrievalSource.VECTOR)));

        Map<String, FusedChunk> byId = index(fusion.fuse(routes, params(1.0d)));

        // The extremes anchor the interval and the middle keeps its relative position.
        assertEquals(1.0d, byId.get("a").normalizedScore(RetrievalSource.VECTOR), TOLERANCE);
        assertEquals(0.5d, byId.get("b").normalizedScore(RetrievalSource.VECTOR), TOLERANCE);
        assertEquals(0.0d, byId.get("c").normalizedScore(RetrievalSource.VECTOR), TOLERANCE);
    }

    @Test
    void shouldWeighTheTwoRoutesAndSumThem() {
        Map<RetrievalSource, List<ScoredChunk>> routes = new EnumMap<>(RetrievalSource.class);
        routes.put(RetrievalSource.VECTOR, List.of(
                new ScoredChunk("shared", 0.9d, RetrievalSource.VECTOR),
                new ScoredChunk("vectorOnly", 0.1d, RetrievalSource.VECTOR)));
        routes.put(RetrievalSource.BM25, List.of(
                new ScoredChunk("bm25Only", 20.0d, RetrievalSource.BM25),
                new ScoredChunk("shared", 10.0d, RetrievalSource.BM25)));

        Map<String, FusedChunk> byId = index(fusion.fuse(routes, params(0.7d)));

        // shared: 0.7 * 1.0 (top of the vector route) + 0.3 * 0.0 (bottom of the BM25 route).
        assertEquals(0.7d, byId.get("shared").getFusedScore(), TOLERANCE);
        // Missing from a route means zero for it, never a neutral value.
        assertEquals(0.0d, byId.get("vectorOnly").getFusedScore(), TOLERANCE);
        assertEquals(0.3d, byId.get("bm25Only").getFusedScore(), TOLERANCE);
    }

    @Test
    void shouldOrderByDescendingFusedScore() {
        Map<RetrievalSource, List<ScoredChunk>> routes = new EnumMap<>(RetrievalSource.class);
        routes.put(RetrievalSource.VECTOR, List.of(
                new ScoredChunk("weak", 0.2d, RetrievalSource.VECTOR),
                new ScoredChunk("strong", 0.1d, RetrievalSource.VECTOR)));
        routes.put(RetrievalSource.BM25, List.of(
                new ScoredChunk("strong", 30.0d, RetrievalSource.BM25),
                new ScoredChunk("weak", 1.0d, RetrievalSource.BM25)));

        List<FusedChunk> fused = fusion.fuse(routes, params(0.2d));

        // The BM25 route carries 0.8 of the weight, so its top candidate wins despite the vector order.
        assertEquals("strong", fused.get(0).getChunkId());
        assertEquals(FusionMode.WEIGHTED, fused.get(0).getFusionMode());
    }

    @Test
    void shouldKeepAConstantScoredRouteAtFullWeight() {
        Map<RetrievalSource, List<ScoredChunk>> routes = new EnumMap<>(RetrievalSource.class);
        routes.put(RetrievalSource.BM25, List.of(
                new ScoredChunk("a", 4.0d, RetrievalSource.BM25),
                new ScoredChunk("b", 4.0d, RetrievalSource.BM25)));

        Map<String, FusedChunk> byId = index(fusion.fuse(routes, params(0.6d)));

        // A zero spread would divide by zero; collapsing to 1.0 keeps the route contributing instead of
        // erasing it from the ranking.
        assertEquals(1.0d, byId.get("a").normalizedScore(RetrievalSource.BM25), TOLERANCE);
        assertEquals(0.4d, byId.get("a").getFusedScore(), TOLERANCE);
        assertEquals(0.4d, byId.get("b").getFusedScore(), TOLERANCE);
    }

    @Test
    void shouldNormaliseASingleCandidateToTheTop() {
        Map<RetrievalSource, List<ScoredChunk>> routes = new EnumMap<>(RetrievalSource.class);
        routes.put(RetrievalSource.VECTOR, List.of(new ScoredChunk("only", 0.42d, RetrievalSource.VECTOR)));

        Map<String, FusedChunk> byId = index(fusion.fuse(routes, params(0.6d)));

        assertEquals(1.0d, byId.get("only").normalizedScore(RetrievalSource.VECTOR), TOLERANCE);
        assertEquals(0.42d, byId.get("only").routeScore(RetrievalSource.VECTOR), TOLERANCE);
    }

    @Test
    void shouldExposeRawScoresAndRanksAlongsideTheNormalisedOnes() {
        Map<RetrievalSource, List<ScoredChunk>> routes = new EnumMap<>(RetrievalSource.class);
        routes.put(RetrievalSource.VECTOR, List.of(
                new ScoredChunk("x", 0.8d, RetrievalSource.VECTOR),
                new ScoredChunk("y", 0.4d, RetrievalSource.VECTOR)));
        routes.put(RetrievalSource.BM25, List.of(
                new ScoredChunk("y", 9.0d, RetrievalSource.BM25)));

        Map<String, FusedChunk> byId = index(fusion.fuse(routes, params(0.5d)));

        FusedChunk y = byId.get("y");
        assertEquals(0.4d, y.routeScore(RetrievalSource.VECTOR), TOLERANCE);
        assertEquals(9.0d, y.routeScore(RetrievalSource.BM25), TOLERANCE);
        assertEquals(2, y.getRouteRanks().get(RetrievalSource.VECTOR));
        assertEquals(1, y.getRouteRanks().get(RetrievalSource.BM25));
        assertNull(byId.get("x").routeScore(RetrievalSource.BM25));
    }

    @Test
    void shouldBeDeterministicOnScoreTies() {
        Map<RetrievalSource, List<ScoredChunk>> routes = new EnumMap<>(RetrievalSource.class);
        routes.put(RetrievalSource.BM25, List.of(
                new ScoredChunk("b", 5.0d, RetrievalSource.BM25),
                new ScoredChunk("a", 5.0d, RetrievalSource.BM25)));

        List<FusedChunk> fused = fusion.fuse(routes, params(0.5d));

        assertEquals(List.of("a", "b"), fused.stream().map(FusedChunk::getChunkId).toList());
    }

    @Test
    void shouldReturnEmptyListWithoutRoutes() {
        assertTrue(fusion.fuse(Map.of(), params(0.6d)).isEmpty());
    }

    @Test
    void shouldRejectAWeightOutsideTheUnitInterval() {
        assertThrows(IllegalArgumentException.class, () -> params(1.5d));
        assertThrows(IllegalArgumentException.class, () -> params(-0.1d));
    }

    private FusionParams params(double wVector) {
        return FusionParams.of(FusionMode.WEIGHTED, FusionParams.DEFAULT_RRF_K, wVector);
    }

    private Map<String, FusedChunk> index(List<FusedChunk> fused) {
        Map<String, FusedChunk> byId = new HashMap<>();
        fused.forEach(chunk -> byId.put(chunk.getChunkId(), chunk));
        return byId;
    }
}
