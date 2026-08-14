package io.kbrag.domain.service;

import io.kbrag.domain.enums.GateReason;
import io.kbrag.domain.enums.GateVerdict;
import io.kbrag.domain.model.AnswerGateConfig;
import io.kbrag.domain.model.FinalAnswerGateComparison;
import io.kbrag.domain.model.FinalAnswerGateDecision;
import io.kbrag.domain.model.FinalAnswerMetrics;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers the final-answer gate's trusted-sample and tolerance rules.
 *
 * @author owlzhangfq@gmail.com
 */
class FinalAnswerGateJudgeTest {

    private final FinalAnswerGateJudge judge = new FinalAnswerGateJudge();

    @Test
    void shouldLogOnlyWhenAnyCommonAnswerJudgmentFailed() {
        FinalAnswerGateDecision decision = judge.judge(comparison(metrics(4.5d), metrics(4.5d), 50, 1),
                new AnswerGateConfig(), 50, 0.2d, 2.0d);

        assertEquals(GateVerdict.LOG_ONLY, decision.verdict());
        assertEquals(GateReason.ANSWER_JUDGE_FAILED, decision.reason());
    }

    @Test
    void shouldBlockWhenAQualityDimensionRegressesBeyondTolerance() {
        FinalAnswerMetrics candidate = new FinalAnswerMetrics(4.5d, 4.5d, 4.1d, 4.5d,
                4.5d, 4.5d, 0.98d, 100, 0, 100);
        FinalAnswerMetrics baseline = new FinalAnswerMetrics(4.5d, 4.5d, 4.4d, 4.5d,
                4.5d, 4.5d, 0.98d, 100, 0, 100);

        FinalAnswerGateDecision decision = judge.judge(comparison(candidate, baseline, 100, 0),
                new AnswerGateConfig(), 50, 0.2d, 2.0d);

        assertEquals(GateVerdict.BLOCKED, decision.verdict());
        assertEquals(GateReason.ANSWER_METRICS_REGRESSED, decision.reason());
    }

    @Test
    void shouldApplyAbsoluteThresholdsOnAFirstRelease() {
        AnswerGateConfig config = new AnswerGateConfig();
        config.setMinScore(4.0d);
        config.setMinFaithfulness(4.0d);
        config.setMinCitationCorrectness(4.0d);
        config.setMinRefusalAccuracy(0.9d);

        FinalAnswerGateDecision decision = judge.judge(
                new FinalAnswerGateComparison(metrics(4.2d), null, 50, 0, 0, List.of()),
                config, 50, 0.2d, 2.0d);

        assertEquals(GateVerdict.PASSED, decision.verdict());
        assertEquals(GateReason.ANSWER_ABSOLUTE_THRESHOLD_MET, decision.reason());
    }

    private FinalAnswerGateComparison comparison(FinalAnswerMetrics candidate, FinalAnswerMetrics baseline,
                                                   int cases, int failures) {
        return new FinalAnswerGateComparison(candidate, baseline, cases, failures, 0, List.of());
    }

    private FinalAnswerMetrics metrics(double value) {
        return new FinalAnswerMetrics(value, value, value, value, value, value, 1.0d, 50, 0, 100);
    }
}
