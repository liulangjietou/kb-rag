package io.kbrag.domain.enums;

/**
 * Nature of the score carried by a retrieval node, required whenever the response is degraded.
 *
 * <p>M1 has no rerank stage, so a node always reports the score of the route that ranked it best;
 * the reciprocal rank fusion value is not a comparable absolute score and is exposed through the
 * node metadata instead of this field.
 */
public enum ScoreType {

    /** Standard cosine similarity normalised to [0,1]. */
    COSINE("cosine"),

    /** Rank based score, the raw BM25 value has no upper bound and is not comparable. */
    BM25_RANK("bm25_rank");

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
