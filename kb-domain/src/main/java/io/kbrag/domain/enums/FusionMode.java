package io.kbrag.domain.enums;

/**
 * Strategy used to merge the candidate lists of the independent recall routes.
 *
 * <p>The two modes answer two different questions. Reciprocal rank fusion only trusts the ordering
 * of each route, which makes it robust when the routes disagree on scale; weighted fusion trusts the
 * magnitudes after a per route min-max normalisation, which lets an operator bias retrieval towards
 * semantics or towards keywords. Neither is universally better, so the mode is a request parameter.
 */
public enum FusionMode {

    /** Rank based fusion, the default. */
    RRF("rrf"),

    /** Weighted sum of the per route min-max normalised scores. */
    WEIGHTED("weighted");

    private final String code;

    FusionMode(String code) {
        this.code = code;
    }

    /**
     * Literal used in the API and in the configuration.
     *
     * @return API side value
     */
    public String code() {
        return code;
    }

    /**
     * Resolves a mode from its literal.
     *
     * @param value configuration or request value, case insensitive
     * @return matching mode, {@link #RRF} when blank or unknown
     */
    public static FusionMode from(String value) {
        if (value == null || value.isBlank()) {
            return RRF;
        }
        for (FusionMode mode : values()) {
            if (mode.code.equalsIgnoreCase(value.trim()) || mode.name().equalsIgnoreCase(value.trim())) {
                return mode;
            }
        }
        return RRF;
    }
}
