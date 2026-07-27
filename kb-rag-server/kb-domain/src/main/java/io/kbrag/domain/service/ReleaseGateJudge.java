package io.kbrag.domain.service;

import io.kbrag.domain.enums.GateReason;
import io.kbrag.domain.enums.GateVerdict;
import io.kbrag.domain.model.AppConfigSnapshot;
import io.kbrag.domain.model.GateComparison;
import io.kbrag.domain.model.GateCoreMetrics;
import io.kbrag.domain.model.GateDecision;
import org.springframework.stereotype.Component;

/**
 * Turns one gate dual run into the three state verdict of requirement section 4.7.
 *
 * <p>A pure function, and the only place the release decision is made. Everything the decision depends on
 * arrives as a plain value, which is what allows every branch below to be pinned down by a unit test with
 * hand picked numbers rather than by two orchestrated evaluation runs.
 *
 * <p><b>The order of the checks is the specification.</b> The four "not decidable" conditions are tested
 * before any metric comparison, because a comparison computed over an untrustworthy sample must not be
 * allowed to produce a {@code PASS} that looks earned - nor a {@code BLOCKED} the requirement explicitly
 * forbids (gating on degraded runs is prohibited by the evaluation rules).
 *
 * <p><b>The tolerance.</b> {@code ε = max(configured percentage points, 1/N)} over the intersection size
 * {@code N}. The second term is what keeps a 50 case data set from blocking on a single case flipping:
 * with {@code N=50} one case is worth 2 percentage points, exactly the floor. A point estimate compared
 * without a tolerance would reject roughly half of all neutral changes.
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class ReleaseGateJudge {

    /** Percentage points per unit, the configured tolerance arrives in percentage points. */
    private static final double PERCENTAGE_POINTS_PER_UNIT = 100.0d;

    /**
     * Slack absorbing binary floating point representation error.
     *
     * <p>Without it a drop that is exactly the tolerance blocks a release: {@code 0.88 - 0.90} evaluates to
     * {@code -0.020000000000000018}, which is below {@code -0.02} by a rounding artefact of the representation
     * rather than by any measured difference. It is deliberately far smaller than any metric difference a case
     * can produce - one case out of the largest realistic data set still moves a metric by orders of magnitude
     * more - so it can never mask a real regression.
     */
    private static final double FLOATING_POINT_SLACK = 1e-9d;

    /**
     * Judges one gate run.
     *
     * @param input everything the decision depends on
     * @return verdict, reason and the numbers behind them
     */
    public GateDecision judge(GateInput input) {
        if (!input.datasetBound()) {
            return GateDecision.of(GateVerdict.LOG_ONLY, GateReason.NO_DATASET);
        }
        if (!input.runsSucceeded() || input.comparison() == null) {
            return GateDecision.of(GateVerdict.LOG_ONLY, GateReason.RUN_FAILED);
        }
        if (!input.runsComparable()) {
            return GateDecision.of(GateVerdict.LOG_ONLY, GateReason.NOT_COMPARABLE);
        }
        GateComparison comparison = input.comparison();
        if (comparison.effectiveCases() < input.minCases()) {
            return GateDecision.of(GateVerdict.LOG_ONLY, GateReason.INSUFFICIENT_CASES);
        }
        if (input.staleRatio() > input.staleRatioThreshold()) {
            return GateDecision.of(GateVerdict.LOG_ONLY, GateReason.STALE_RATIO_EXCEEDED);
        }
        if (comparison.degradedCases() > 0) {
            return GateDecision.of(GateVerdict.LOG_ONLY, GateReason.DEGRADED_CASES);
        }
        if (!comparison.hasBaseline()) {
            return judgeFirstRelease(comparison.candidate(), input.thresholds());
        }
        return compare(comparison, input.epsilonPercentagePoints());
    }

    /**
     * Tolerance applied to a comparison of {@code n} common cases.
     *
     * @param effectiveCases           intersection size
     * @param epsilonPercentagePoints  configured floor in percentage points
     * @return tolerance as a fraction
     */
    public double epsilon(int effectiveCases, double epsilonPercentagePoints) {
        double floor = epsilonPercentagePoints / PERCENTAGE_POINTS_PER_UNIT;
        if (effectiveCases <= 0) {
            return floor;
        }
        return Math.max(floor, 1.0d / effectiveCases);
    }

    /**
     * First release with no baseline, requirement section 4.7 "first release baseline rule".
     *
     * <p>Never blocks for the absence of a baseline: it either measures against the absolute thresholds an
     * operator configured, or it records the run as the baseline of every later release and lets the
     * version through. Blocking a first release would make the gate impossible to adopt at all, since no
     * application can have a released version before its first one.
     *
     * @param candidate  candidate metrics
     * @param thresholds configured absolute thresholds, {@code null} when none
     * @return verdict of a first release
     */
    private GateDecision judgeFirstRelease(GateCoreMetrics candidate,
                                           AppConfigSnapshot.GateThresholds thresholds) {
        if (thresholds == null || !thresholds.configured()) {
            return GateDecision.of(GateVerdict.PASSED, GateReason.BASELINE_RECORDED);
        }
        boolean hitRateOk = thresholds.minHitRate() == null || candidate.hitRate() >= thresholds.minHitRate();
        boolean recallOk = thresholds.minRecall() == null || candidate.recall() >= thresholds.minRecall();
        return hitRateOk && recallOk
                ? GateDecision.of(GateVerdict.PASSED, GateReason.ABSOLUTE_THRESHOLD_MET)
                : GateDecision.of(GateVerdict.BLOCKED, GateReason.ABSOLUTE_THRESHOLD_NOT_MET);
    }

    /**
     * Compares candidate against baseline with the tolerance.
     *
     * @param comparison              intersection metrics of both sides
     * @param epsilonPercentagePoints configured tolerance floor in percentage points
     * @return verdict carrying the tolerance and both deltas
     */
    private GateDecision compare(GateComparison comparison, double epsilonPercentagePoints) {
        double epsilon = epsilon(comparison.effectiveCases(), epsilonPercentagePoints);
        GateCoreMetrics candidate = comparison.candidate();
        GateCoreMetrics baseline = comparison.baseline();
        double hitRateDelta = candidate.hitRate() - baseline.hitRate();
        double recallDelta = candidate.recall() - baseline.recall();
        // Strictly below baseline minus epsilon blocks; equal to it does not, which is what "only when the
        // candidate is below the tolerance band" means - hence the representation slack on the boundary.
        double bound = -epsilon - FLOATING_POINT_SLACK;
        boolean regressed = hitRateDelta < bound || recallDelta < bound;
        return new GateDecision(regressed ? GateVerdict.BLOCKED : GateVerdict.PASSED,
                regressed ? GateReason.METRICS_REGRESSED : GateReason.WITHIN_TOLERANCE,
                epsilon, hitRateDelta, recallDelta);
    }

    /**
     * Everything one gate decision depends on.
     *
     * @param datasetBound            {@code true} when the version binds a baseline data set
     * @param runsSucceeded           {@code true} when every run of the dual run finished successfully
     * @param runsComparable          {@code true} when both runs measured the same data set revision and
     *                                the same corpus state; always {@code true} for a first release
     * @param comparison              intersection metrics, {@code null} when no run produced any
     * @param staleRatio              pending review cases over total cases of the data set
     * @param thresholds              absolute thresholds of a first release, {@code null} when none
     * @param minCases                minimum intersection size the comparison is trusted at
     * @param epsilonPercentagePoints tolerance floor in percentage points
     * @param staleRatioThreshold     pending review ratio above which the data set counts as invalid
     */
    public record GateInput(
            boolean datasetBound,
            boolean runsSucceeded,
            boolean runsComparable,
            GateComparison comparison,
            double staleRatio,
            AppConfigSnapshot.GateThresholds thresholds,
            int minCases,
            double epsilonPercentagePoints,
            double staleRatioThreshold) {
    }
}
