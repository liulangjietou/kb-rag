package io.kbrag.domain.service;

import io.kbrag.domain.model.FinalAnswerCaseOutcome;
import io.kbrag.domain.model.FinalAnswerGateComparison;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers common-case recomputation for the final-answer dual run.
 *
 * @author owlzhangfq@gmail.com
 */
class FinalAnswerGateRecomputerTest {

    private final FinalAnswerGateRecomputer recomputer =
            new FinalAnswerGateRecomputer(new FinalAnswerMetricsCalculator());

    @Test
    void shouldUseOnlyCommonSuccessfullyJudgedCasesAndSurfaceFailures() {
        List<FinalAnswerCaseOutcome> candidate = List.of(
                judged("c1", 5), failed("c2"), judged("candidate-only", 1));
        List<FinalAnswerCaseOutcome> baseline = List.of(
                judged("c1", 4), judged("c2", 1), judged("baseline-only", 5));

        FinalAnswerGateComparison comparison = recomputer.recompute(candidate, baseline);

        assertEquals(1, comparison.effectiveCases());
        assertEquals(1, comparison.judgeFailedCases());
        assertEquals(List.of("c1"), comparison.caseIds());
        assertEquals(5.0d, comparison.candidate().score());
        assertEquals(4.0d, comparison.baseline().score());
    }

    private FinalAnswerCaseOutcome judged(String caseId, int score) {
        return new FinalAnswerCaseOutcome(caseId, score, score, score, score, score, score,
                true, 10, true, false);
    }

    private FinalAnswerCaseOutcome failed(String caseId) {
        return new FinalAnswerCaseOutcome(caseId, null, null, null, null, null, null,
                null, 10, true, false);
    }
}
