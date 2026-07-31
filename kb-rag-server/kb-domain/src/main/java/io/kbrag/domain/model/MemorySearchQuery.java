package io.kbrag.domain.model;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

/**
 * Recall request issued against the memory index.
 *
 * <p>Library and entity are mandatory filters - isolation is enforced here, not by the caller
 * remembering to add a clause. The vector is optional: without it recall degrades to BM25, the
 * same degradation contract as the knowledge base search.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Builder
@ToString(exclude = "embedding")
public class MemorySearchQuery {

    /** Library to search, the application isolation predicate. */
    private final String libraryId;

    /** Memory entity to search, the in-library isolation predicate. */
    private final String userId;

    /** Query text for BM25 recall. */
    private final String queryText;

    /** Query vector for semantic recall, {@code null} to recall by BM25 alone. */
    private final float[] embedding;

    /** Restricts recall to one fragment rule, {@code null} for all rules. */
    private final String ruleId;

    /** Number of candidates to recall. */
    private final int topK;
}
