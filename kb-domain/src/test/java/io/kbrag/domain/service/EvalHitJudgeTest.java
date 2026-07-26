package io.kbrag.domain.service;

import io.kbrag.domain.model.CaseJudgment;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the case level hit judgment of requirement section 4.6: multi evidence Hit Rate and
 * Recall@K, the document anchored path, and the child level aggregate coverage of a two level
 * knowledge base.
 *
 * @author owlzhangfq@gmail.com
 */
class EvalHitJudgeTest {

    private static final double THRESHOLD = 0.5d;

    private final EvalHitJudge hitJudge = new EvalHitJudge(new OverlapRatioCalculator(new ChunkTextHasher()));

    @Test
    void shouldHitOnAnyEvidenceAndRecallOnlyTheCoveredOnes() {
        String spanA = "ABCDEFGHIJ";
        String spanB = "0123456789";
        // Rank 1 fully answers spanA, rank 2 is unrelated noise, spanB is never recalled.
        List<List<String>> perRank = List.of(
                List.of(spanA),
                List.of("unrelated noise"));

        CaseJudgment judgment = hitJudge.judgeSpanCase(List.of(spanA, spanB), perRank, THRESHOLD);

        assertTrue(judgment.hit(), "hit rate takes the case on any evidence match");
        assertEquals(1, judgment.hitRank());
        assertEquals(1, judgment.evidenceHitCount());
        assertEquals(2, judgment.evidenceTotalCount());
        // Recall@K = evidences covered / evidences declared, not "any evidence" like the hit rate.
        assertEquals(0.5d, judgment.recallFraction());
        assertEquals(List.of(true, false), judgment.relevancePerRank());
    }

    @Test
    void shouldCoverEveryEvidenceAndReportTheEarliestHitRank() {
        String spanA = "ABCDEFGHIJ";
        String spanB = "0123456789";
        List<List<String>> perRank = List.of(
                List.of(spanA),
                List.of("unrelated noise"),
                List.of(spanB));

        CaseJudgment judgment = hitJudge.judgeSpanCase(List.of(spanA, spanB), perRank, THRESHOLD);

        assertTrue(judgment.hit());
        assertEquals(1, judgment.hitRank(), "the earliest evidence hit wins the case level rank");
        assertEquals(2, judgment.evidenceHitCount());
        assertEquals(1.0d, judgment.recallFraction());
        assertEquals(Arrays.asList(true, false, true), judgment.relevancePerRank());
    }

    @Test
    void shouldMissWhenNoRankOverlapsAnyEvidence() {
        List<List<String>> perRank = List.of(List.of("xyz"), List.of("qwerty"));

        CaseJudgment judgment = hitJudge.judgeSpanCase(List.of("ABCDEFGHIJ"), perRank, THRESHOLD);

        assertFalse(judgment.hit());
        assertNull(judgment.hitRank());
        assertEquals(0, judgment.evidenceHitCount());
        assertEquals(0.0d, judgment.recallFraction());
        assertEquals(List.of(false, false), judgment.relevancePerRank());
    }

    @Test
    void shouldAggregateTheChildTextsOfATwoLevelRankIntoOneCoverageUnion() {
        // One returned parent unit whose two children each cover half the span; neither child alone
        // reaches the threshold but the runner supplies both texts of that single rank together, which
        // is exactly "aggregate coverage on the child set of the top K parent units".
        String span = "ABCDEFGHIJ";
        List<List<String>> perRank = List.of(List.of("ABCDE", "FGHIJ"));

        CaseJudgment judgment = hitJudge.judgeSpanCase(List.of(span), perRank, 0.6d);

        assertTrue(judgment.hit());
        assertEquals(1, judgment.hitRank());
        assertEquals(1.0d, judgment.recallFraction());
    }

    @Test
    void shouldJudgeADocumentAnchoredCaseByDocIdWithoutOverlapComputation() {
        List<String> evidenceDocIds = List.of("doc_a", "doc_b");
        List<String> docIdPerRank = Arrays.asList("doc_x", "doc_a", "doc_c");

        CaseJudgment judgment = hitJudge.judgeDocumentCase(evidenceDocIds, docIdPerRank);

        assertTrue(judgment.hit());
        assertEquals(2, judgment.hitRank());
        assertEquals(1, judgment.evidenceHitCount());
        assertEquals(2, judgment.evidenceTotalCount());
        assertEquals(0.5d, judgment.recallFraction());
        assertEquals(Arrays.asList(false, true, false), judgment.relevancePerRank());
    }

    @Test
    void shouldMissADocumentAnchoredCaseWhenNoRankMatchesTheAnchoredDocument() {
        CaseJudgment judgment = hitJudge.judgeDocumentCase(
                List.of("doc_a"), Arrays.asList("doc_x", "doc_y"));

        assertFalse(judgment.hit());
        assertNull(judgment.hitRank());
        assertEquals(0.0d, judgment.recallFraction());
    }
}
