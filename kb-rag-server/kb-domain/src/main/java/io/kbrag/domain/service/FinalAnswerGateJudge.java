package io.kbrag.domain.service;

import io.kbrag.domain.enums.GateReason;
import io.kbrag.domain.enums.GateVerdict;
import io.kbrag.domain.model.AnswerGateConfig;
import io.kbrag.domain.model.FinalAnswerGateComparison;
import io.kbrag.domain.model.FinalAnswerGateDecision;
import io.kbrag.domain.model.FinalAnswerMetricDeltas;
import io.kbrag.domain.model.FinalAnswerMetrics;
import org.springframework.stereotype.Component;

/**
 * Pure final-answer release gate policy.
 *
 * <p>Provider and structured-output failures always produce {@code LOG_ONLY}: they say nothing about the
 * candidate's quality. A candidate is blocked only when valid common-case metrics cross a configured
 * tolerance, which keeps infrastructure failure and model regression as distinct operational facts.
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class FinalAnswerGateJudge {

    private static final double PERCENTAGE_POINTS_PER_UNIT = 100.0d;
    private static final double FLOATING_POINT_SLACK = 1e-9d;

    /**
     * Judges a final-answer comparison.
     *
     * @param comparison common-case metrics, {@code null} when no answer run completed
     * @param config application version's opt-in answer gate configuration
     * @param minCases minimum trusted sample size
     * @param scoreEpsilon tolerance on one-to-five scores
     * @param accuracyEpsilonPp tolerance floor on refusal accuracy, in percentage points
     * @return final-answer gate decision
     */
    public FinalAnswerGateDecision judge(FinalAnswerGateComparison comparison, AnswerGateConfig config,
                                         int minCases, double scoreEpsilon,
                                         double accuracyEpsilonPp) {
        if (comparison == null) {
            return FinalAnswerGateDecision.of(GateVerdict.LOG_ONLY, GateReason.ANSWER_EVAL_UNAVAILABLE);
        }
        if (comparison.judgeFailedCases() > 0) {
            return FinalAnswerGateDecision.of(GateVerdict.LOG_ONLY, GateReason.ANSWER_JUDGE_FAILED);
        }
        if (comparison.effectiveCases() < minCases) {
            return FinalAnswerGateDecision.of(GateVerdict.LOG_ONLY, GateReason.ANSWER_INSUFFICIENT_CASES);
        }
        if (comparison.degradedCases() > 0) {
            return FinalAnswerGateDecision.of(GateVerdict.LOG_ONLY, GateReason.DEGRADED_CASES);
        }
        if (!comparison.hasBaseline()) {
            return judgeFirstRelease(comparison.candidate(), config);
        }
        return compare(comparison, scoreEpsilon, accuracyEpsilonPp);
    }

    private FinalAnswerGateDecision judgeFirstRelease(FinalAnswerMetrics candidate,
                                                       AnswerGateConfig config) {
        if (config == null || !config.thresholdsConfigured()) {
            return FinalAnswerGateDecision.of(GateVerdict.PASSED, GateReason.ANSWER_BASELINE_RECORDED);
        }
        boolean passed = atLeast(candidate.score(), config.getMinScore())
                && atLeast(candidate.faithfulness(), config.getMinFaithfulness())
                && atLeast(candidate.citationCorrectness(), config.getMinCitationCorrectness())
                && atLeast(candidate.refusalAccuracy(), config.getMinRefusalAccuracy());
        return FinalAnswerGateDecision.of(passed ? GateVerdict.PASSED : GateVerdict.BLOCKED,
                passed ? GateReason.ANSWER_ABSOLUTE_THRESHOLD_MET
                        : GateReason.ANSWER_ABSOLUTE_THRESHOLD_NOT_MET);
    }

    private FinalAnswerGateDecision compare(FinalAnswerGateComparison comparison, double scoreEpsilon,
                                             double accuracyEpsilonPp) {
        FinalAnswerMetrics candidate = comparison.candidate();
        FinalAnswerMetrics baseline = comparison.baseline();
        FinalAnswerMetricDeltas deltas = new FinalAnswerMetricDeltas(
                candidate.score() - baseline.score(),
                candidate.correctness() - baseline.correctness(),
                candidate.faithfulness() - baseline.faithfulness(),
                candidate.completeness() - baseline.completeness(),
                candidate.citationCorrectness() - baseline.citationCorrectness(),
                candidate.citationCompleteness() - baseline.citationCompleteness(),
                candidate.refusalAccuracy() - baseline.refusalAccuracy());
        double effectiveScoreEpsilon = Math.max(0.0d, scoreEpsilon);
        double accuracyEpsilon = Math.max(accuracyEpsilonPp / PERCENTAGE_POINTS_PER_UNIT,
                1.0d / comparison.effectiveCases());
        double scoreBound = -effectiveScoreEpsilon - FLOATING_POINT_SLACK;
        double accuracyBound = -accuracyEpsilon - FLOATING_POINT_SLACK;
        boolean regressed = deltas.score() < scoreBound || deltas.correctness() < scoreBound
                || deltas.faithfulness() < scoreBound || deltas.completeness() < scoreBound
                || deltas.citationCorrectness() < scoreBound
                || deltas.citationCompleteness() < scoreBound
                || deltas.refusalAccuracy() < accuracyBound;
        return new FinalAnswerGateDecision(regressed ? GateVerdict.BLOCKED : GateVerdict.PASSED,
                regressed ? GateReason.ANSWER_METRICS_REGRESSED : GateReason.ANSWER_WITHIN_TOLERANCE,
                effectiveScoreEpsilon, accuracyEpsilon, deltas);
    }

    private boolean atLeast(double actual, Double threshold) {
        return threshold == null || actual + FLOATING_POINT_SLACK >= threshold;
    }
}
