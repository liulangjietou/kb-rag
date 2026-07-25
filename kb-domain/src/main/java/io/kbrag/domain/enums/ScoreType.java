package io.kbrag.domain.enums;

/**
 * Nature of the score carried by a retrieval node, required whenever the response is degraded.
 *
 * <p>The value always describes the score actually reported in {@code score}. When a threshold is
 * supplied the reported score is the one the threshold acts on, so the console can verify the
 * filter; otherwise it is the score that ordered the final list. Rerank is the only absolute score
 * comparable across queries, which is why it wins whenever the stage ran.
 */
public enum ScoreType {

    /** Rerank relevance normalised to [0,1] by the provider, the only absolute score. */
    RERANK("rerank"),

    /** Standard cosine similarity normalised to [0,1]. */
    COSINE("cosine"),

    /** Rank based score, the raw BM25 value has no upper bound and is not comparable. */
    BM25_RANK("bm25_rank"),

    /** Reciprocal rank fusion score, ordering only. */
    FUSED_RRF("fused_rrf"),

    /** Weighted fusion of the per route min-max normalised scores, ordering only. */
    FUSED_WEIGHTED("fused_weighted");

    private final String code;

    ScoreType(String code) {
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
