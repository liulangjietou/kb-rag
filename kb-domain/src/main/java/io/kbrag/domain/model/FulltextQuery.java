package io.kbrag.domain.model;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

/**
 * BM25 request issued against a full text index.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Builder
@ToString
public class FulltextQuery {

    /** Raw user query. */
    private final String queryText;

    /** Number of candidates to recall. */
    private final int topK;

    /** Mandatory version and enabled filter. */
    private final RetrievalFilter filter;
}
