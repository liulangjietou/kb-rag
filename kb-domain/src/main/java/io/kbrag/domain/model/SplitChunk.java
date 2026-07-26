package io.kbrag.domain.model;

import lombok.Getter;
import lombok.ToString;

import java.util.Map;

/**
 * Output of a splitter: one chunk with its position and estimated token count.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@ToString(exclude = "content")
public class SplitChunk {

    /** Zero based order inside the source text. */
    private final int seq;

    /** Chunk text. */
    private final String content;

    /** Estimated token count of {@link #content}. */
    private final int tokenCount;

    /**
     * Title, summary and keywords the LLM semantic splitter produces alongside a chunk, merged into
     * {@code t_kb_chunk.metadata}; {@code null} for every other strategy.
     */
    private final Map<String, Object> metadata;

    public SplitChunk(int seq, String content, int tokenCount) {
        this(seq, content, tokenCount, null);
    }

    public SplitChunk(int seq, String content, int tokenCount, Map<String, Object> metadata) {
        this.seq = seq;
        this.content = content;
        this.tokenCount = tokenCount;
        this.metadata = metadata;
    }
}
