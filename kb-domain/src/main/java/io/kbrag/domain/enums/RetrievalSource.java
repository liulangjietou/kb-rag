package io.kbrag.domain.enums;

/**
 * Route a retrieval node was recalled from.
 */
public enum RetrievalSource {

    /** Vector kNN route. */
    VECTOR,

    /** BM25 full text route. */
    BM25,

    /** Graph route, reserved for M7. */
    GRAPH;

    /**
     * Lower case literal returned by the API.
     *
     * @return API side value
     */
    public String code() {
        return name().toLowerCase();
    }
}
