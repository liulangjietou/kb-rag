package io.kbrag.domain.model;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

/**
 * One recalled memory node with its relevance score.
 *
 * <p>Content travels with the hit because the rerank step consumes it before any MySQL hydration
 * happens; the authoritative record is still re-read from MySQL when the response is assembled.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Builder
@ToString
public class MemoryHit {

    /** Memory node business id. */
    private final String nodeId;

    /** Content as stored in the index. */
    private final String content;

    /** Normalised relevance score in {@code [0, 1]}, comparable against the similarity threshold. */
    private final double score;
}
