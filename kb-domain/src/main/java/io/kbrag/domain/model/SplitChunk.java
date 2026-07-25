package io.kbrag.domain.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

/**
 * Output of a splitter: one chunk with its position and estimated token count.
 */
@Getter
@AllArgsConstructor
@ToString(exclude = "content")
public class SplitChunk {

    /** Zero based order inside the source text. */
    private final int seq;

    /** Chunk text. */
    private final String content;

    /** Estimated token count of {@link #content}. */
    private final int tokenCount;
}
