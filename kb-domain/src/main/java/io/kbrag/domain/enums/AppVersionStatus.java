package io.kbrag.domain.enums;

import java.util.EnumSet;
import java.util.Set;

/**
 * Release state machine of an application version, requirement section 4.7 "asynchronous release
 * state machine".
 *
 * <p>The allowed transitions live on the enum rather than inside the release service so every caller -
 * submit for test, release, forced release, rollback - is judged by the same table, and an illegal
 * move is rejected before any row is touched. Reading the set below is the whole specification:
 *
 * <pre>
 * DRAFT -&gt; TESTING -&gt; GATING -&gt; GATE_PASSED    -&gt; RELEASED -&gt; SUPERSEDED
 *                            -&gt; GATE_LOG_ONLY -&gt; RELEASED (forced, recorded)
 *                            -&gt; GATE_BLOCKED  -&gt; RELEASED (forced, recorded)
 * SUPERSEDED -&gt; RELEASED (rollback)
 * </pre>
 *
 * <p><b>Why the three gate outcomes are states and not a boolean.</b> The requirement collapses six
 * observable gate results into three ({@code pass} / {@code block} / {@code log only}) precisely
 * because a state machine that accepts two of them has to map "not decidable" onto "blocked", which
 * would contradict the evaluation rules that forbid gating on a degraded run.
 *
 * @author owlzhangfq@gmail.com
 */
public enum AppVersionStatus {

    /** Editable configuration, the only status whose configuration may still change. */
    DRAFT,

    /** Submitted as a test version: callable through the open API with an explicit version parameter. */
    TESTING,

    /** Gate dual run in flight; the release request already returned and the console polls. */
    GATING,

    /** Gate compared the two runs and the candidate did not regress beyond the tolerance. */
    GATE_PASSED,

    /** Gate could not decide; releasing needs an explicit forced release that is recorded. */
    GATE_LOG_ONLY,

    /** Gate found a regression beyond the tolerance; releasing needs a recorded forced release. */
    GATE_BLOCKED,

    /** The single version the open API serves when no version parameter is supplied. */
    RELEASED,

    /** Retired by a newer release or by a rollback; no longer callable, requirement section 4.7. */
    SUPERSEDED;

    private static final Set<AppVersionStatus> GATE_OUTCOMES =
            EnumSet.of(GATE_PASSED, GATE_LOG_ONLY, GATE_BLOCKED);

    /**
     * Tells whether this status may move to the given one.
     *
     * @param target status being moved to
     * @return {@code true} when the move is part of the state machine
     */
    public boolean canTransitionTo(AppVersionStatus target) {
        if (target == null || target == this) {
            return false;
        }
        return switch (this) {
            case DRAFT -> target == TESTING;
            // A gate can be re-run from a test version, and a version whose gate could not be decided
            // may either be forced through or re-gated once the data set was repaired.
            case TESTING -> target == GATING || GATE_OUTCOMES.contains(target);
            case GATING -> GATE_OUTCOMES.contains(target);
            case GATE_PASSED -> target == RELEASED || target == GATING;
            case GATE_LOG_ONLY, GATE_BLOCKED -> target == RELEASED || target == GATING;
            case RELEASED -> target == SUPERSEDED;
            case SUPERSEDED -> target == RELEASED;
        };
    }

    /**
     * Tells whether a version in this status is callable through the open API.
     *
     * <p>{@link #TESTING} is callable only with an explicit version parameter, which the caller side
     * enforces; this method answers the narrower question of whether the version may serve traffic at
     * all, which {@link #SUPERSEDED} and every pre release status may not.
     *
     * @return {@code true} for the released and the test version
     */
    public boolean callable() {
        return this == RELEASED || this == TESTING;
    }

    /**
     * Tells whether the configuration snapshot of a version in this status may still be edited.
     *
     * @return {@code true} only for {@link #DRAFT}
     */
    public boolean configEditable() {
        return this == DRAFT;
    }

    /**
     * Tells whether this status is a decided gate outcome.
     *
     * @return {@code true} for the three gate result states
     */
    public boolean gateOutcome() {
        return GATE_OUTCOMES.contains(this);
    }
}
