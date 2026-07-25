package io.kbrag.domain.enums;

/**
 * Degradation markers returned in the {@code degraded} array of a search response.
 *
 * <p>A marker means a stage the caller asked for could not run as promised. A stage that is simply
 * switched off is not a degradation and never produces a marker, which is what keeps the zero key
 * deployment free of noise: rewrite and rerank disappear silently when no model is configured, and
 * only an explicit request for them yields {@link #QUERY_REWRITE_UNAVAILABLE} or
 * {@link #RERANK_UNAVAILABLE}.
 */
public enum DegradedReason {

    /** Query rewrite exceeded its budget, the original query was used. */
    QUERY_REWRITE_TIMEOUT("query_rewrite_timeout"),

    /** Query rewrite failed, the original query was used. */
    QUERY_REWRITE_ERROR("query_rewrite_error"),

    /** Rewrite was explicitly requested but no chat model is configured. */
    QUERY_REWRITE_UNAVAILABLE("query_rewrite_unavailable"),

    /** Rerank was explicitly requested but no rerank model is configured. */
    RERANK_UNAVAILABLE("rerank_unavailable"),

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
