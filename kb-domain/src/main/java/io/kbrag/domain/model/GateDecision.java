package io.kbrag.domain.model;

import io.kbrag.domain.enums.GateReason;
import io.kbrag.domain.enums.GateVerdict;

/**
 * Verdict of the release gate together with the numbers it was derived from.
 *
 * <p>The tolerance and the two deltas travel with the verdict so the console can show <em>why</em> a
 * release was blocked rather than only that it was, and so a forced release records the exact figures the
 * operator overrode.
 *
 * @param verdict      three state outcome
 * @param reason       classified explanation
 * @param epsilon      tolerance actually applied, {@code 0} when no comparison took place
 * @param hitRateDelta candidate minus baseline {@code Hit Rate}, {@code null} without a comparison
 * @param recallDelta  candidate minus baseline {@code Recall@K}, {@code null} without a comparison
 *
 * @author owlzhangfq@gmail.com
 */
public record GateDecision(
        GateVerdict verdict,
        GateReason reason,
        double epsilon,
        Double hitRateDelta,
        Double recallDelta) {

    /**
     * Builds a decision that no comparison backs, such as a missing data set.
     *
     * @param verdict three state outcome
     * @param reason  classified explanation
     * @return decision with no tolerance and no deltas
     */
    public static GateDecision of(GateVerdict verdict, GateReason reason) {
        return new GateDecision(verdict, reason, 0.0d, null, null);
    }
}
