package io.kbrag.app.retrieval;

import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.enums.ChunkType;
import io.kbrag.domain.enums.FusionMode;
import io.kbrag.domain.enums.RetrievalSource;
import io.kbrag.domain.enums.ScoreType;
import io.kbrag.domain.model.FusedChunk;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the four pipeline shapes a threshold can meet, because each one leaves a different score as
 * the only comparable one: rerank applied, rerank switched off, rerank degraded, and the BM25 single
 * route where no comparable score exists at all.
 */
class ScoreThresholdPolicyTest {

    private static final double TOLERANCE = 1e-9;

    private final ScoreThresholdPolicy policy = new ScoreThresholdPolicy();

    @Test
    void shouldActOnRerankWhenTheStageRan() {
        ScoreThresholdPolicy.ThresholdDecision decision = policy.decide(0.5d, true, true);

        assertEquals(ThresholdTarget.RERANK, decision.getTarget());
        assertTrue(decision.isActive());
        assertFalse(decision.isInactive());
        assertEquals(ScoreType.RERANK.code(), decision.appliedOn());

        RetrievalCandidate candidate = candidate("ck_1", 0.42d, 0.88d, 7.5d);
        candidate.applyRerankScore(0.73d);
        assertEquals(0.73d, policy.thresholdScore(candidate, decision), TOLERANCE);

        ScoreThresholdPolicy.ReportedScore reported = policy.report(candidate, decision, FusionMode.RRF);
        assertEquals(ScoreType.RERANK, reported.getType());
        assertEquals(0.73d, reported.getValue(), TOLERANCE);
    }

    @Test
    void shouldFallBackToCosineWhenRerankIsSwitchedOff() {
        ScoreThresholdPolicy.ThresholdDecision decision = policy.decide(0.6d, false, true);

        assertEquals(ThresholdTarget.COSINE, decision.getTarget());
        assertTrue(decision.isActive());
        assertEquals(ScoreType.COSINE.code(), decision.appliedOn());

        RetrievalCandidate candidate = candidate("ck_1", 0.42d, 0.88d, 7.5d);
        assertEquals(0.88d, policy.thresholdScore(candidate, decision), TOLERANCE);

        ScoreThresholdPolicy.ReportedScore reported = policy.report(candidate, decision, FusionMode.RRF);
        assertEquals(ScoreType.COSINE, reported.getType());
        assertEquals(0.88d, reported.getValue(), TOLERANCE);
    }

    @Test
    void shouldFallBackToCosineWhenRerankDegraded() {
        // A degraded rerank is indistinguishable from a switched off one at this point: no candidate
        // carries a cross encoder score, so the cosine similarity is again the only comparable value.
        ScoreThresholdPolicy.ThresholdDecision decision = policy.decide(0.6d, false, true);
        RetrievalCandidate candidate = candidate("ck_1", 0.42d, 0.55d, 7.5d);

        assertEquals(ThresholdTarget.COSINE, decision.getTarget());
        assertEquals(0.55d, policy.thresholdScore(candidate, decision), TOLERANCE);
        assertNull(candidate.getRerankScore());
    }

    @Test
    void shouldDropTheThresholdOnASingleBm25Route() {
        ScoreThresholdPolicy.ThresholdDecision decision = policy.decide(0.6d, false, false);

        assertEquals(ThresholdTarget.NONE, decision.getTarget());
        assertFalse(decision.isActive());
        // Requested but impossible: the caller has to be told, otherwise a threshold that filtered
        // nothing looks like a threshold that everything passed.
        assertTrue(decision.isInactive());
        assertEquals("none", decision.appliedOn());
        assertNull(policy.thresholdScore(candidate("ck_1", 0.02d, null, 7.5d), decision));
    }

    @Test
    void shouldReportTheRawBm25ScoreOnASingleRoute() {
        ScoreThresholdPolicy.ThresholdDecision decision = policy.decide(null, false, false);
        RetrievalCandidate candidate = candidate("ck_1", 0.016d, null, 7.5d);

        ScoreThresholdPolicy.ReportedScore reported = policy.report(candidate, decision, FusionMode.RRF);

        assertEquals(ScoreType.BM25_RANK, reported.getType());
        assertEquals(7.5d, reported.getValue(), TOLERANCE);
    }

    @Test
    void shouldReportTheFusionScoreWhenNothingIsFiltered() {
        ScoreThresholdPolicy.ThresholdDecision decision = policy.decide(null, false, true);
        RetrievalCandidate candidate = candidate("ck_1", 0.031d, 0.88d, 7.5d);

        assertFalse(decision.isActive());
        assertFalse(decision.isInactive());
        assertEquals("none", decision.appliedOn());

        ScoreThresholdPolicy.ReportedScore rrf = policy.report(candidate, decision, FusionMode.RRF);
        assertEquals(ScoreType.FUSED_RRF, rrf.getType());
        assertEquals(0.031d, rrf.getValue(), TOLERANCE);

        ScoreThresholdPolicy.ReportedScore weighted = policy.report(candidate, decision, FusionMode.WEIGHTED);
        assertEquals(ScoreType.FUSED_WEIGHTED, weighted.getType());
    }

    @Test
    void shouldReportZeroForACandidateTheActiveTargetCannotScore() {
        ScoreThresholdPolicy.ThresholdDecision decision = policy.decide(0.6d, false, true);
        // Recalled by BM25 only while the vector route did run: there is no cosine evidence for it.
        RetrievalCandidate candidate = candidate("ck_1", 0.016d, null, 7.5d);

        assertNull(policy.thresholdScore(candidate, decision));
        ScoreThresholdPolicy.ReportedScore reported = policy.report(candidate, decision, FusionMode.RRF);
        assertEquals(ScoreType.COSINE, reported.getType());
        assertEquals(0.0d, reported.getValue(), TOLERANCE);
    }

    @Test
    void shouldNotReportAnInactiveMarkerWhenNoThresholdWasRequested() {
        assertFalse(policy.decide(null, false, false).isInactive());
    }

    private RetrievalCandidate candidate(String chunkId, double fusedScore, Double cosine, Double bm25) {
        Map<RetrievalSource, Double> scores = new EnumMap<>(RetrievalSource.class);
        Map<RetrievalSource, Integer> ranks = new EnumMap<>(RetrievalSource.class);
        if (cosine != null) {
            scores.put(RetrievalSource.VECTOR, cosine);
            ranks.put(RetrievalSource.VECTOR, 1);
        }
        if (bm25 != null) {
            scores.put(RetrievalSource.BM25, bm25);
            ranks.put(RetrievalSource.BM25, 1);
        }
        FusedChunk fused = FusedChunk.builder()
                .chunkId(chunkId)
                .fusedScore(fusedScore)
                .fusionMode(FusionMode.RRF)
                .primarySource(cosine != null ? RetrievalSource.VECTOR : RetrievalSource.BM25)
                .routeRanks(ranks)
                .routeScores(scores)
                .normalizedScores(Map.of())
                .build();

        Chunk chunk = new Chunk();
        chunk.setChunkId(chunkId);
        chunk.setContent("content of " + chunkId);
        chunk.setChunkType(ChunkType.TEXT);
        chunk.setEnabled(1);
        chunk.setSeq(0);
        return new RetrievalCandidate(fused, chunk);
    }
}
