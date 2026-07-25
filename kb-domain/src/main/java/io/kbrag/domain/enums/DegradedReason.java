package io.kbrag.domain.enums;

/**
 * Degradation markers returned in the {@code degraded} array of a search response.
 *
 * <p>M1 can only emit {@link #VECTOR_ROUTE_UNAVAILABLE}; the remaining values are part of the
 * published contract and land with the rewrite and rerank stages in M2.
 */
public enum DegradedReason {

    /** Query rewrite timed out, the original query was used. */
    QUERY_REWRITE_TIMEOUT("query_rewrite_timeout"),

    /** Rerank call timed out, the coarse fusion result was returned. */
    RERANK_TIMEOUT("rerank_timeout"),

    /** Rerank call failed, the coarse fusion result was returned. */
    RERANK_ERROR("rerank_error"),

    /** No embedding provider configured or vector route unusable, BM25 single route. */
    VECTOR_ROUTE_UNAVAILABLE("vector_route_unavailable"),

    /** Router white list not matched, all linked knowledge bases were searched. */
    ROUTE_FALLBACK_ALL("route_fallback_all"),

    /** Score threshold not applied because the active score type is not comparable. */
    THRESHOLD_INACTIVE("threshold_inactive");

    private final String code;

    DegradedReason(String code) {
        this.code = code;
    }

    /**
     * Literal returned by the API.
     *
     * @return API side value
     */
    public String code() {
        return code;
    }
}
