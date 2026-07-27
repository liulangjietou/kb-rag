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

    /**
     * Start offset of {@link #content} inside the text this chunk was cut from, {@code null} when the
     * strategy cannot report one.
     *
     * <p>Reported by the splitter itself rather than recovered afterwards by matching the text back into
     * its source: the position is a by-product of the cut, and a strategy that rewrites the text - the LLM
     * semantic one - has no position to report at all, which is exactly what {@code null} says.
     */
    private final Integer startOffset;

    /** Exclusive end offset of {@link #content} inside its source text, {@code null} with {@link #startOffset}. */
    private final Integer endOffset;

    public SplitChunk(int seq, String content, int tokenCount) {
        this(seq, content, tokenCount, null);
    }

    public SplitChunk(int seq, String content, int tokenCount, Map<String, Object> metadata) {
        this(seq, content, tokenCount, metadata, null, null);
    }

    public SplitChunk(int seq, String content, int tokenCount, Map<String, Object> metadata,
                      Integer startOffset, Integer endOffset) {
        this.seq = seq;
        this.content = content;
        this.tokenCount = tokenCount;
        this.metadata = metadata;
        this.startOffset = startOffset;
        this.endOffset = endOffset;
    }
}
