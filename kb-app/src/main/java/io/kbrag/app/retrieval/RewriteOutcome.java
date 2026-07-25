package io.kbrag.app.retrieval;

import lombok.Getter;
import lombok.ToString;

/**
 * Result of the query rewrite stage.
 *
 * <p>The stage never fails a search: it either produces a better query or hands the original one
 * back together with the marker explaining why, which is what lets the caller keep a single code
 * path regardless of the model being reachable.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@ToString
public final class RewriteOutcome {

    /** Query the recall stage must use. */
    private final String query;

    /** Degradation marker, {@code null} when nothing degraded. */
    private final String degradedReason;

    /** {@code true} when the query actually went through the model. */
    private final boolean rewritten;

    private RewriteOutcome(String query, String degradedReason, boolean rewritten) {
        this.query = query;
        this.degradedReason = degradedReason;
        this.rewritten = rewritten;
    }

    /**
     * The stage was off or produced nothing usable, and that is not a degradation.
     *
     * @param originalQuery query to search with
     * @return outcome without a marker
     */
    public static RewriteOutcome skipped(String originalQuery) {
        return new RewriteOutcome(originalQuery, null, false);
    }

    /**
     * The stage produced a rewritten query.
     *
     * @param rewrittenQuery query to search with
     * @return outcome without a marker
     */
    public static RewriteOutcome rewritten(String rewrittenQuery) {
        return new RewriteOutcome(rewrittenQuery, null, true);
    }

    /**
     * The stage was requested but could not run.
     *
     * @param originalQuery  query to search with
     * @param degradedReason marker explaining the fallback
     * @return outcome carrying the marker
     */
    public static RewriteOutcome degraded(String originalQuery, String degradedReason) {
        return new RewriteOutcome(originalQuery, degradedReason, false);
    }
}
