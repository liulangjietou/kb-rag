package io.kbrag.domain.service;

import io.kbrag.domain.model.FinalAnswerCaseOutcome;
import io.kbrag.domain.model.FinalAnswerMetrics;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers final-answer metric aggregation and its failure accounting.
 *
 * @author owlzhangfq@gmail.com
 */
class FinalAnswerMetricsCalculatorTest {

    private final FinalAnswerMetricsCalculator calculator = new FinalAnswerMetricsCalculator();

    @Test
    void shouldExcludeJudgeFailuresFromMeansAndCountThemSeparately() {
        FinalAnswerCaseOutcome judged = outcome("c1", 5, 4, 100);
        FinalAnswerCaseOutcome failed = new FinalAnswerCaseOutcome("c2", null, null, null,
                null, null, null, null, 300, true, false);

        FinalAnswerMetrics metrics = calculator.aggregate(List.of(judged, failed));

        assertEquals(5.0d, metrics.score());
        assertEquals(4.0d, metrics.faithfulness());
        assertEquals(1, metrics.evaluatedCases());
        assertEquals(1, metrics.judgeFailedCases());
        assertEquals(100, metrics.latencyP95Ms());
    }

    @Test
    void shouldCalculateP95WithNearestRank() {
        FinalAnswerMetrics metrics = calculator.aggregate(List.of(
                outcome("c1", 5, 5, 10), outcome("c2", 4, 4, 20),
                outcome("c3", 3, 3, 30), outcome("c4", 2, 2, 40)));

        assertEquals(40, metrics.latencyP95Ms());
        assertEquals(3.5d, metrics.score());
    }

    private FinalAnswerCaseOutcome outcome(String caseId, int score, int faithfulness, int latency) {
        return new FinalAnswerCaseOutcome(caseId, score, score, faithfulness, score, score, score,
                true, latency, true, false);
    }
}
