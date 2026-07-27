package io.kbrag.domain.service;

import io.kbrag.domain.enums.FusionMode;
import io.kbrag.domain.enums.RetrievalSource;
import io.kbrag.domain.model.FusedChunk;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the cross base merge of requirement section 4.7: the order comes from the in base rank and never
 * from the in base score, and the route evidence survives the rewrite of the fusion score.
 *
 * @author owlzhangfq@gmail.com
 */
class CrossKbRrfFusionTest {

    private static final int RRF_K = 60;

    private final CrossKbRrfFusion fusion = new CrossKbRrfFusion();

    @Test
    void shouldInterleaveBasesByTheirInBaseRank() {
        // The scores below are deliberately incomparable: base b's numbers are an order of magnitude larger,
        // which is exactly what happens when two bases use different embedding models. Only the rank counts.
        Map<String, List<FusedChunk>> rankedByKb = new LinkedHashMap<>();
        rankedByKb.put("kb_a", List.of(candidate("a1", 0.02d), candidate("a2", 0.01d), candidate("a3", 0.005d)));
        rankedByKb.put("kb_b", List.of(candidate("b1", 9.0d), candidate("b2", 8.0d)));

        List<FusedChunk> merged = fusion.merge(rankedByKb, RRF_K);

        assertEquals(List.of("a1", "b1", "a2", "b2", "a3"),
                merged.stream().map(FusedChunk::getChunkId).toList());
    }

    @Test
    void shouldReplaceTheInBaseScoreWithTheReciprocalRankScore() {
        Map<String, List<FusedChunk>> rankedByKb = new LinkedHashMap<>();
        rankedByKb.put("kb_a", List.of(candidate("a1", 0.02d), candidate("a2", 0.01d)));
        rankedByKb.put("kb_b", List.of(candidate("b1", 9.0d)));

        List<FusedChunk> merged = fusion.merge(rankedByKb, RRF_K);

        assertEquals(1.0d / (RRF_K + 1), merged.get(0).getFusedScore());
        assertEquals(1.0d / (RRF_K + 1), merged.get(1).getFusedScore());
        assertEquals(1.0d / (RRF_K + 2), merged.get(2).getFusedScore());
        // Cross base merging is reciprocal rank fusion whatever the in base strategy was.
        assertTrue(merged.stream().allMatch(chunk -> chunk.getFusionMode() == FusionMode.RRF));
    }

    @Test
    void shouldHonourTheConfiguredDampingConstant() {
        Map<String, List<FusedChunk>> rankedByKb = new LinkedHashMap<>();
        rankedByKb.put("kb_a", List.of(candidate("a1", 0.02d)));
        rankedByKb.put("kb_b", List.of(candidate("b1", 9.0d), candidate("b2", 8.0d)));

        List<FusedChunk> merged = fusion.merge(rankedByKb, 1);

        assertEquals(0.5d, merged.get(0).getFusedScore());
        assertEquals(0.5d, merged.get(1).getFusedScore());
        assertEquals(1.0d / 3, merged.get(2).getFusedScore());
    }

    @Test
    void shouldCarryTheRouteEvidenceThrough() {
        Map<String, List<FusedChunk>> rankedByKb = new LinkedHashMap<>();
        rankedByKb.put("kb_a", List.of(candidate("a1", 0.02d)));
        rankedByKb.put("kb_b", List.of(candidate("b1", 9.0d)));

        FusedChunk merged = fusion.merge(rankedByKb, RRF_K).get(0);

        // The debug page still has to be able to explain what each route contributed inside its own base.
        assertEquals(7.5d, merged.routeScore(RetrievalSource.BM25));
        assertEquals(1, merged.getRouteRanks().get(RetrievalSource.BM25));
        assertEquals(RetrievalSource.BM25, merged.getPrimarySource());
    }

    @Test
    void shouldReturnNothingWithoutContributingBases() {
        assertTrue(fusion.merge(Map.of(), RRF_K).isEmpty());
        assertTrue(fusion.merge(Map.of("kb_a", List.of()), RRF_K).isEmpty());
    }

    private FusedChunk candidate(String chunkId, double inKbScore) {
        return FusedChunk.builder()
                .chunkId(chunkId)
                .fusedScore(inKbScore)
                .fusionMode(FusionMode.WEIGHTED)
                .primarySource(RetrievalSource.BM25)
                .routeRanks(Map.of(RetrievalSource.BM25, 1))
                .routeScores(Map.of(RetrievalSource.BM25, 7.5d))
                .normalizedScores(Map.of(RetrievalSource.BM25, 1.0d))
                .build();
    }
}
