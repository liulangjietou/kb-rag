package io.kbrag.domain.service;

import io.kbrag.domain.enums.GateReason;
import io.kbrag.domain.enums.GateVerdict;
import io.kbrag.domain.model.AppConfigSnapshot;
import io.kbrag.domain.model.GateComparison;
import io.kbrag.domain.model.GateCoreMetrics;
import io.kbrag.domain.model.GateDecision;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins down every branch of the release gate verdict of requirement section 4.7: the four "record, do not
 * block" conditions, the tolerance arithmetic, the regression comparison and the first release rules.
 *
 * @author owlzhangfq@gmail.com
 */
class ReleaseGateJudgeTest {

    private static final int MIN_CASES = 50;
    private static final double EPSILON_PP = 2.0d;
    private static final double STALE_RATIO_THRESHOLD = 0.15d;

    private final ReleaseGateJudge judge = new ReleaseGateJudge();

    @Test
    void shouldRecordWithoutBlockingWhenNoDataSetIsBound() {
        GateDecision decision = judge.judge(input(false, true, true, null, 0.0d, null));

        assertEquals(GateVerdict.LOG_ONLY, decision.verdict());
        assertEquals(GateReason.NO_DATASET, decision.reason());
        assertEquals(0.0d, decision.epsilon());
        assertNull(decision.hitRateDelta());
    }

    @Test
    void shouldRecordWithoutBlockingWhenARunDidNotSucceed() {
        GateDecision decision = judge.judge(input(true, false, true,
                comparison(0.9d, 0.9d, 0.5d, 0.5d, 60, 0), 0.0d, null));

        assertEquals(GateVerdict.LOG_ONLY, decision.verdict());
        assertEquals(GateReason.RUN_FAILED, decision.reason());
    }

    @Test
    void shouldRecordWithoutBlockingWhenTheTwoRunsAreNotComparable() {
        GateDecision decision = judge.judge(input(true, true, false,
                comparison(0.9d, 0.9d, 0.5d, 0.5d, 60, 0), 0.0d, null));

        assertEquals(GateVerdict.LOG_ONLY, decision.verdict());
        assertEquals(GateReason.NOT_COMPARABLE, decision.reason());
    }

    @Test
    void shouldRecordWithoutBlockingWhenTheEffectiveCaseCountIsBelowTheMinimum() {
        // 49 common cases against a minimum of 50: one case short is still short, and a comparison on a
        // sample the requirement declares untrustworthy must not produce a blocking verdict.
        GateDecision decision = judge.judge(input(true, true, true,
                comparison(0.20d, 0.90d, 0.10d, 0.90d, 49, 0), 0.0d, null));

        assertEquals(GateVerdict.LOG_ONLY, decision.verdict());
        assertEquals(GateReason.INSUFFICIENT_CASES, decision.reason());
    }

    @Test
    void shouldRecordWithoutBlockingWhenTheStaleRatioExceedsTheThreshold() {
        GateDecision decision = judge.judge(input(true, true, true,
                comparison(0.20d, 0.90d, 0.10d, 0.90d, 60, 0), 0.16d, null));

        assertEquals(GateVerdict.LOG_ONLY, decision.verdict());
        assertEquals(GateReason.STALE_RATIO_EXCEEDED, decision.reason());
    }

    @Test
    void shouldNotTripTheStaleRatioExactlyAtTheThreshold() {
        GateDecision decision = judge.judge(input(true, true, true,
                comparison(0.90d, 0.90d, 0.90d, 0.90d, 60, 0), STALE_RATIO_THRESHOLD, null));

        assertEquals(GateVerdict.PASSED, decision.verdict());
        assertEquals(GateReason.WITHIN_TOLERANCE, decision.reason());
    }

    @Test
    void shouldRecordWithoutBlockingWhenAnyCaseStayedDegraded() {
        GateDecision decision = judge.judge(input(true, true, true,
                comparison(0.20d, 0.90d, 0.10d, 0.90d, 60, 1), 0.0d, null));

        assertEquals(GateVerdict.LOG_ONLY, decision.verdict());
        assertEquals(GateReason.DEGRADED_CASES, decision.reason());
    }

    @Test
    void shouldBlockWhenHitRateFallsBelowTheBaselineMinusTheTolerance() {
        // 60 cases: tolerance is max(0.02, 1/60) = 0.02. A 5 point drop is well outside it.
        GateDecision decision = judge.judge(input(true, true, true,
                comparison(0.85d, 0.90d, 0.70d, 0.70d, 60, 0), 0.0d, null));

        assertEquals(GateVerdict.BLOCKED, decision.verdict());
        assertEquals(GateReason.METRICS_REGRESSED, decision.reason());
        assertEquals(0.02d, decision.epsilon(), 1e-9d);
        assertEquals(-0.05d, decision.hitRateDelta(), 1e-9d);
        assertEquals(0.0d, decision.recallDelta(), 1e-9d);
    }

    @Test
    void shouldBlockWhenRecallAloneFallsOutsideTheTolerance() {
        GateDecision decision = judge.judge(input(true, true, true,
                comparison(0.90d, 0.90d, 0.60d, 0.70d, 60, 0), 0.0d, null));

        assertEquals(GateVerdict.BLOCKED, decision.verdict());
        assertEquals(GateReason.METRICS_REGRESSED, decision.reason());
        assertEquals(-0.10d, decision.recallDelta(), 1e-9d);
    }

    @Test
    void shouldPassWhenTheDropStaysInsideTheTolerance() {
        // A 1 point drop on 60 cases is inside the 2 point floor: the requirement's tolerance exists exactly
        // so that a single case flipping does not block a release.
        GateDecision decision = judge.judge(input(true, true, true,
                comparison(0.89d, 0.90d, 0.70d, 0.70d, 60, 0), 0.0d, null));

        assertEquals(GateVerdict.PASSED, decision.verdict());
        assertEquals(GateReason.WITHIN_TOLERANCE, decision.reason());
    }

    @Test
    void shouldPassWhenTheDropEqualsTheToleranceExactly() {
        GateDecision decision = judge.judge(input(true, true, true,
                comparison(0.88d, 0.90d, 0.70d, 0.70d, 60, 0), 0.0d, null));

        assertEquals(GateVerdict.PASSED, decision.verdict());
        assertEquals(-0.02d, decision.hitRateDelta(), 1e-9d);
    }

    @Test
    void shouldWidenTheToleranceOnASmallSample() {
        // 20 common cases with a minimum of 10: the floor is 2 points but one case is worth 5, so the
        // tolerance becomes 1/20 and a 4 point drop no longer blocks.
        GateDecision decision = judge.judge(new ReleaseGateJudge.GateInput(true, true, true,
                comparison(0.86d, 0.90d, 0.70d, 0.70d, 20, 0), 0.0d, null,
                10, EPSILON_PP, STALE_RATIO_THRESHOLD));

        assertEquals(0.05d, decision.epsilon(), 1e-9d);
        assertEquals(GateVerdict.PASSED, decision.verdict());
    }

    @Test
    void shouldComputeTheToleranceFloorWhenThereIsNoSample() {
        assertEquals(0.02d, judge.epsilon(0, EPSILON_PP), 1e-9d);
        assertEquals(0.02d, judge.epsilon(50, EPSILON_PP), 1e-9d);
        assertEquals(0.04d, judge.epsilon(25, EPSILON_PP), 1e-9d);
    }

    @Test
    void shouldRecordTheBaselineAndReleaseOnAFirstReleaseWithoutThresholds() {
        GateDecision decision = judge.judge(input(true, true, true,
                firstRelease(0.42d, 0.31d, 60), 0.0d, null));

        assertEquals(GateVerdict.PASSED, decision.verdict());
        assertEquals(GateReason.BASELINE_RECORDED, decision.reason());
        assertNull(decision.hitRateDelta());
    }

    @Test
    void shouldPassAFirstReleaseThatMeetsItsAbsoluteThresholds() {
        GateDecision decision = judge.judge(input(true, true, true,
                firstRelease(0.80d, 0.60d, 60), 0.0d,
                new AppConfigSnapshot.GateThresholds(0.80d, 0.55d)));

        assertEquals(GateVerdict.PASSED, decision.verdict());
        assertEquals(GateReason.ABSOLUTE_THRESHOLD_MET, decision.reason());
    }

    @Test
    void shouldBlockAFirstReleaseThatMissesOneAbsoluteThreshold() {
        GateDecision decision = judge.judge(input(true, true, true,
                firstRelease(0.80d, 0.50d, 60), 0.0d,
                new AppConfigSnapshot.GateThresholds(0.80d, 0.55d)));

        assertEquals(GateVerdict.BLOCKED, decision.verdict());
        assertEquals(GateReason.ABSOLUTE_THRESHOLD_NOT_MET, decision.reason());
    }

    @Test
    void shouldIgnoreAnEmptyThresholdBlockOnAFirstRelease() {
        GateDecision decision = judge.judge(input(true, true, true,
                firstRelease(0.10d, 0.10d, 60), 0.0d,
                new AppConfigSnapshot.GateThresholds(null, null)));

        assertEquals(GateVerdict.PASSED, decision.verdict());
        assertEquals(GateReason.BASELINE_RECORDED, decision.reason());
    }

    private ReleaseGateJudge.GateInput input(boolean datasetBound, boolean runsSucceeded, boolean comparable,
                                             GateComparison comparison, double staleRatio,
                                             AppConfigSnapshot.GateThresholds thresholds) {
        return new ReleaseGateJudge.GateInput(datasetBound, runsSucceeded, comparable, comparison, staleRatio,
                thresholds, MIN_CASES, EPSILON_PP, STALE_RATIO_THRESHOLD);
    }

    private GateComparison comparison(double candidateHitRate, double baselineHitRate, double candidateRecall,
                                      double baselineRecall, int effectiveCases, int degradedCases) {
        return new GateComparison(new GateCoreMetrics(candidateHitRate, candidateRecall),
                new GateCoreMetrics(baselineHitRate, baselineRecall), effectiveCases, degradedCases, List.of());
    }

    private GateComparison firstRelease(double hitRate, double recall, int effectiveCases) {
        return new GateComparison(new GateCoreMetrics(hitRate, recall), null, effectiveCases, 0, List.of());
    }
}
