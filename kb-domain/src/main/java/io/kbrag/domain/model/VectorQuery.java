package io.kbrag.domain.model;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

/**
 * kNN request issued against a vector index.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Builder
@ToString(exclude = "queryVector")
public class VectorQuery {

    /** Embedded query vector. */
    private final float[] queryVector;

    /** Number of candidates to recall. */
    private final int topK;

    /** Mandatory version and enabled filter. */
    private final RetrievalFilter filter;
}
