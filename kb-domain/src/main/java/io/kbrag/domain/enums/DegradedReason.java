package io.kbrag.domain.enums;

/**
 * Degradation markers returned in the {@code degraded} array of a search response.
 *
 * <p>A marker means a stage the caller asked for could not run as promised. A stage that is simply
 * switched off is not a degradation and never produces a marker, which is what keeps the zero key
 * deployment free of noise: rewrite and rerank disappear silently when no model is configured, and
 * only an explicit request for them yields {@link #QUERY_REWRITE_UNAVAILABLE} or
 * {@link #RERANK_UNAVAILABLE}.
 *
 * @author owlzhangfq@gmail.com
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
    THRESHOLD_INACTIVE("threshold_inactive"),

    /**
     * A released version's frozen snapshot index is gone from the engine, so the call was served from the
     * live alias and the current active versions instead of the corpus the version was released on.
     *
     * <p>Only reported when a snapshot was recorded and turned out to be missing. A version released before
     * index snapshots existed has nothing frozen and is served from the live alias by design, which is a
     * historical data shape rather than a fault and carries no marker.
     */
    SNAPSHOT_INDEX_MISSING("snapshot_index_missing"),

    /**
     * The knowledge base has the graph route switched on but the graph is not reachable, so the call ran
     * on the vector and BM25 routes alone, requirement section 4.9.
     *
     * <p>Reported for an unreachable or unconfigured graph only. A released version served from an index
     * snapshot switches the graph route off by design - the graph holds active version semantics and has
     * no frozen copy - which is a capability boundary rather than a fault and carries no marker.
     */
    GRAPH_ROUTE_UNAVAILABLE("graph_route_unavailable");

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
