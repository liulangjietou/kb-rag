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
    GRAPH_ROUTE_UNAVAILABLE("graph_route_unavailable"),

    /**
     * The call carried images but none of them could be turned into text, so the retrieval ran on the
     * written query alone, requirement section 4.8.
     *
     * <p>Reported whenever an image was sent and its understanding did not happen - no vision model
     * configured, a timeout, a provider failure. Unlike the rewrite and rerank markers there is no "not
     * requested" case to distinguish: attaching an image <em>is</em> the request, so a caller that gets no
     * image semantics into its query always has to be told.
     */
    IMAGE_UNDERSTANDING_UNAVAILABLE("image_understanding_unavailable"),

    /**
     * The knowledge base has the multimodal route switched on but the {@code fusion_mode} is
     * {@code weighted}, so the route did not run, the M14 contract section 6.3.
     *
     * <p>Reciprocal rank fusion is the only mode that can merge a route lacking a comparable absolute
     * score, exactly the reason the graph route is refused under weighted fusion. The multimodal
     * vector route shares that constraint, so it is skipped rather than folded in on an incompatible
     * scale, and the caller is told which route it lost.
     */
    MM_ROUTE_SKIPPED("mm_route_skipped"),

    /**
     * The knowledge base has the multimodal route switched on but embedding the query into the
     * multimodal space failed or timed out, so the call ran on the remaining routes, the M14 contract
     * section 6.3.
     *
     * <p>Reported for a configured but unreachable multimodal provider only. A knowledge base whose
     * switch is off never asked for the route and carries no marker, the same silence the vector route
     * keeps in zero key mode.
     */
    MM_ROUTE_UNAVAILABLE("mm_route_unavailable");

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
