package io.kbrag.domain.model;

import io.kbrag.domain.enums.FusionMode;
import lombok.Getter;
import lombok.ToString;

/**
 * Validated fusion parameters.
 *
 * <p>Only the vector weight is a request parameter: the two weights must sum to one for the fused
 * score to stay inside {@code [0,1]}, so deriving the BM25 weight here removes an inconsistent
 * combination from the API surface entirely.
 */
@Getter
@ToString
public final class FusionParams {

    /** Damping constant the original reciprocal rank fusion publication reports as stable. */
    public static final int DEFAULT_RRF_K = 60;

    /** Default vector weight of the weighted strategy. */
    public static final double DEFAULT_W_VECTOR = 0.6d;

    /** Selected strategy. */
    private final FusionMode mode;

    /** Damping constant of the reciprocal rank strategy. */
    private final int rrfK;

    /** Weight of the vector route in the weighted strategy. */
    private final double wVector;

    private FusionParams(FusionMode mode, int rrfK, double wVector) {
        this.mode = mode;
        this.rrfK = rrfK;
        this.wVector = wVector;
    }

    /**
     * Builds a validated parameter set.
     *
     * @param mode    strategy, {@code null} falls back to reciprocal rank fusion
     * @param rrfK    damping constant, must be positive
     * @param wVector vector weight, must be within {@code [0,1]}
     * @return validated parameters
     */
    public static FusionParams of(FusionMode mode, int rrfK, double wVector) {
        if (rrfK <= 0) {
            throw new IllegalArgumentException("rrf k must be positive");
        }
        if (wVector < 0.0d || wVector > 1.0d) {
            throw new IllegalArgumentException("w_vec must be within [0,1]");
        }
        return new FusionParams(mode == null ? FusionMode.RRF : mode, rrfK, wVector);
    }

    /**
     * Builds the contract default parameter set.
     *
     * @return reciprocal rank fusion with k=60
     */
    public static FusionParams defaults() {
        return new FusionParams(FusionMode.RRF, DEFAULT_RRF_K, DEFAULT_W_VECTOR);
    }

    /**
     * Weight of the BM25 route, derived so both weights always sum to one.
     *
     * @return BM25 weight
     */
    public double wBm25() {
        return 1.0d - wVector;
    }
}
