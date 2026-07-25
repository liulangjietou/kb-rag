package io.kbrag.app.retrieval;

import lombok.Getter;
import lombok.ToString;

import java.util.List;

/**
 * Result of the rerank stage.
 *
 * <p>Like the rewrite stage, rerank never fails a search: it either produces one score per candidate
 * or reports that the coarse ranking has to stand, so the caller keeps a single code path.
 */
@Getter
@ToString
public final class RerankOutcome {

    /** Relevance score per candidate, aligned with the submitted order; empty when not applied. */
    private final List<Double> scores;

    /** Degradation marker, {@code null} when nothing degraded. */
    private final String degradedReason;

    /** {@code true} when the scores may be used to reorder the candidates. */
    private final boolean applied;

    private RerankOutcome(List<Double> scores, String degradedReason, boolean applied) {
        this.scores = scores;
        this.degradedReason = degradedReason;
        this.applied = applied;
    }

    /**
     * The stage was off, and that is not a degradation.
     *
     * @return outcome without scores and without a marker
     */
    public static RerankOutcome skipped() {
        return new RerankOutcome(List.of(), null, false);
    }

    /**
     * The stage scored every candidate.
     *
     * @param scores relevance score per candidate
     * @return outcome carrying the scores
     */
    public static RerankOutcome applied(List<Double> scores) {
        return new RerankOutcome(scores, null, true);
    }

    /**
     * The stage was requested but could not run.
     *
     * @param degradedReason marker explaining the fallback
     * @return outcome carrying the marker
     */
    public static RerankOutcome degraded(String degradedReason) {
        return new RerankOutcome(List.of(), degradedReason, false);
    }
}
