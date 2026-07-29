package io.kbrag.domain.enums;

/**
 * Route a retrieval node was recalled from.
 *
 * @author owlzhangfq@gmail.com
 */
public enum RetrievalSource {

    /** Vector kNN route. */
    VECTOR,

    /** BM25 full text route. */
    BM25,

    /** Multimodal vector route, the M14 contract section 6.3. */
    MM,

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
