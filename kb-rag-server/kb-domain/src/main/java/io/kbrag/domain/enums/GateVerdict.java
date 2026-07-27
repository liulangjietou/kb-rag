package io.kbrag.domain.enums;

/**
 * Three state outcome of the release gate, requirement section 4.7.
 *
 * <p>Deliberately three values and not two: "the comparison could not be trusted" is a different
 * answer from "the candidate is worse", and mapping the first onto the second would block releases for
 * reasons the evaluation rules explicitly forbid gating on, such as a run that came back degraded.
 *
 * @author owlzhangfq@gmail.com
 */
public enum GateVerdict {

    /** Candidate did not regress beyond the tolerance, or cleared the configured absolute threshold. */
    PASSED(AppVersionStatus.GATE_PASSED),

    /** Candidate regressed beyond the tolerance; a release from here has to be forced and recorded. */
    BLOCKED(AppVersionStatus.GATE_BLOCKED),

    /** Comparison not decidable; recorded, never auto released, forced release stays available. */
    LOG_ONLY(AppVersionStatus.GATE_LOG_ONLY);

    private final AppVersionStatus status;

    GateVerdict(AppVersionStatus status) {
        this.status = status;
    }

    /**
     * Version status this verdict puts the candidate in.
     *
     * @return matching state machine status
     */
    public AppVersionStatus status() {
        return status;
    }

    /**
     * Tells whether the release may continue without an operator forcing it.
     *
     * @return {@code true} only for {@link #PASSED}
     */
    public boolean autoReleasable() {
        return this == PASSED;
    }
}
