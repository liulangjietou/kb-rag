package io.kbrag.domain.enums;

/**
 * Retrieval configuration matrix an evaluation run can compare, see requirement section 4.6
 * "same data set, several retrieval configurations".
 *
 * <p>Each mode maps onto the existing {@code RetrievalCommand} parameters rather than introducing a
 * parallel retrieval path: the evaluation runner reuses {@code RetrievalService} for every mode and
 * only changes which routes are allowed to run and whether rerank is requested. This is deliberate -
 * copying the retrieval pipeline for the evaluation runner would let the two drift apart and would
 * make a passing evaluation meaningless for the traffic actually served.
 *
 * @author owlzhangfq@gmail.com
 */
public enum EvalMode {

    /** BM25 route only, vector route forced off regardless of embedding availability. */
    BM25_ONLY(false, true, false),

    /** Vector route only, BM25 route forced off. */
    VECTOR_ONLY(true, false, false),

    /** Both routes, fused, rerank off. */
    HYBRID(true, true, false),

    /** Both routes, fused, rerank on. */
    HYBRID_RERANK(true, true, true);

    private final boolean requiresVector;
    private final boolean bm25RouteEnabled;
    private final boolean rerankRequested;

    EvalMode(boolean requiresVector, boolean bm25RouteEnabled, boolean rerankRequested) {
        this.requiresVector = requiresVector;
        this.bm25RouteEnabled = bm25RouteEnabled;
        this.rerankRequested = rerankRequested;
    }

    /**
     * Tells whether this mode needs a working embedding provider to produce a trustworthy result.
     *
     * <p>Used by the run submission to fail fast in a zero key deployment instead of silently
     * degrading into a BM25 only result labelled as a vector or hybrid one.
     *
     * @return {@code true} for every mode that reads the vector route
     */
    public boolean requiresVector() {
        return requiresVector;
    }

    /**
     * Tells whether the BM25 route should be allowed to run.
     *
     * @return {@code true} unless this mode is {@link #VECTOR_ONLY}
     */
    public boolean bm25RouteEnabled() {
        return bm25RouteEnabled;
    }

    /**
     * Tells whether the vector route should be allowed to run.
     *
     * @return {@code true} unless this mode is {@link #BM25_ONLY}
     */
    public boolean vectorRouteEnabled() {
        return requiresVector;
    }

    /**
     * Tells whether rerank should be requested for this mode.
     *
     * @return {@code true} only for {@link #HYBRID_RERANK}
     */
    public boolean rerankRequested() {
        return rerankRequested;
    }

    /**
     * Resolves a mode from its literal, case insensitively.
     *
     * @param value request literal
     * @return matching mode
     * @throws IllegalArgumentException when the literal matches no mode
     */
    public static EvalMode from(String value) {
        if (value != null) {
            for (EvalMode mode : values()) {
                if (mode.name().equalsIgnoreCase(value.trim())) {
                    return mode;
                }
            }
        }
        throw new IllegalArgumentException("unknown evaluation mode: " + value);
    }
}
