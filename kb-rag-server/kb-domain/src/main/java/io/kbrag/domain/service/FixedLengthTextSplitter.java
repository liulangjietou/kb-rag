package io.kbrag.domain.service;

import io.kbrag.domain.model.SplitChunk;
import io.kbrag.domain.model.SplitParams;
import io.kbrag.domain.port.TokenEstimator;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Fixed length splitting strategy with overlap, the M1 default.
 *
 * <p>The algorithm works in two stages so cuts land on natural boundaries instead of in the middle
 * of a sentence:
 * <ol>
 *   <li>the text is broken into atomic segments at blank lines, line breaks and sentence
 *       terminators;</li>
 *   <li>segments are packed greedily until the next one would exceed the token budget, and the
 *       trailing segments of the emitted chunk are replayed as the overlap of the next one.</li>
 * </ol>
 *
 * <p>A segment longer than the whole budget cannot be packed, so it is hard cut on the character
 * position that matches the budget. That case is expected for tables and code blocks and never
 * silently drops content.
 *
 * <p><b>Every segment carries its position in the source text</b> (M9, requirement section 4.5). A packed
 * chunk is always a contiguous run of segments, so its offsets are the first segment's start plus whatever
 * the leading trim removed - a by-product of the packing rather than a search performed afterwards. The
 * two level splitter turns those offsets into {@code t_kb_chunk.parent_start_offset}, which is what lets a
 * disabled child be cut out of its parent text precisely.
 *
 * <p>{@code @Primary} since M4b: a second {@link TextSplitter} bean joined the container
 * ({@link LlmSemanticTextSplitter}), and every collaborator that still autowires a single
 * {@code TextSplitter} directly - the two level splitter and the LLM strategy's own fallback path -
 * has to keep resolving to this one without being rewritten against {@link SplitterRouter}.
 *
 * @author owlzhangfq@gmail.com
 */
@Primary
@Component
@RequiredArgsConstructor
public class FixedLengthTextSplitter implements TextSplitter {

    /** Strategy code persisted in the split fingerprint. */
    public static final String STRATEGY_CODE = "fixed_length";

    /**
     * Characters that terminate an atomic segment: line breaks plus the sentence terminators of both
     * Latin and CJK punctuation.
     *
     * <p>The ASCII period also splits decimals and abbreviations, which is harmless: segments are
     * rejoined verbatim during packing, so the only effect is where a chunk boundary is allowed to
     * fall once the token budget runs out.
     */
    private static final String SEGMENT_TERMINATORS = "\n.!?;。！？；";

    /** Upper bound of the characters {@link String#trim()} removes, mirrored so offsets match the text. */
    private static final char TRIM_BOUNDARY = ' ';

    /** Newline that rejoins the blocks the M14 separator and heading strategies pack together. */
    private static final String BLOCK_JOINER = "\n";

    private final TokenEstimator tokenEstimator;

    @Override
    public String strategy() {
        return STRATEGY_CODE;
    }

    @Override
    public List<SplitChunk> split(String text, SplitParams params) {
        List<SplitChunk> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }
        List<Segment> segments = toSegments(text, params.getMaxTokens());

        List<Segment> buffer = new ArrayList<>();
        int bufferTokens = 0;
        int seq = 0;
        for (Segment segment : segments) {
            int segmentTokens = tokenEstimator.estimate(segment.text());
            if (!buffer.isEmpty() && bufferTokens + segmentTokens > params.getMaxTokens()) {
                chunks.add(newChunk(seq++, buffer));
                // Cap the replayed overlap so the next chunk still fits into the budget.
                int overlapBudget = Math.min(params.getOverlapTokens(), params.getMaxTokens() - segmentTokens);
                buffer = tailOverlap(buffer, overlapBudget);
                bufferTokens = totalTokens(buffer);
            }
            buffer.add(segment);
            bufferTokens += segmentTokens;
        }
        if (!buffer.isEmpty()) {
            SplitChunk last = newChunk(seq, buffer);
            if (!last.getContent().isBlank()) {
                chunks.add(last);
            }
        }
        return chunks;
    }

    /**
     * Packs pre-segmented blocks into chunks, the packing half of the fixed length strategy the M14
     * {@code separator} and {@code heading} strategies reuse rather than each owning an overflow path
     * of its own, the contract section 4.
     *
     * <p>A block that fits is packed greedily with its neighbours up to the token budget; a block that
     * alone exceeds the budget is re-split by the full fixed length algorithm so an over long paragraph
     * or heading section still lands in chunks that respect {@code chunk_max_tokens} instead of being
     * emitted whole. The packed chunks carry no source offsets: a strategy that packs blocks cannot be
     * combined with parent child splitting, which is the only reader of an offset.
     *
     * @param blocks logical blocks in reading order, blank blocks ignored
     * @param params split parameters
     * @return ordered chunks
     */
    public List<SplitChunk> packBlocks(List<String> blocks, SplitParams params) {
        List<SplitChunk> chunks = new ArrayList<>();
        if (blocks == null || blocks.isEmpty()) {
            return chunks;
        }
        List<String> buffer = new ArrayList<>();
        int bufferTokens = 0;
        int seq = 0;
        for (String block : blocks) {
            if (block == null || block.isBlank()) {
                continue;
            }
            int blockTokens = tokenEstimator.estimate(block);
            if (blockTokens > params.getMaxTokens()) {
                if (!buffer.isEmpty()) {
                    chunks.add(newBlockChunk(seq++, buffer));
                    buffer = new ArrayList<>();
                    bufferTokens = 0;
                }
                for (SplitChunk piece : split(block, params)) {
                    chunks.add(new SplitChunk(seq++, piece.getContent(), piece.getTokenCount()));
                }
                continue;
            }
            if (!buffer.isEmpty() && bufferTokens + blockTokens > params.getMaxTokens()) {
                chunks.add(newBlockChunk(seq++, buffer));
                buffer = new ArrayList<>();
                bufferTokens = 0;
            }
            buffer.add(block);
            bufferTokens += blockTokens;
        }
        if (!buffer.isEmpty()) {
            chunks.add(newBlockChunk(seq, buffer));
        }
        return chunks;
    }

    private SplitChunk newBlockChunk(int seq, List<String> blocks) {
        String content = String.join(BLOCK_JOINER, blocks).trim();
        return new SplitChunk(seq, content, tokenEstimator.estimate(content));
    }

    /**
     * Breaks the source text into atomic segments, hard cutting any segment that alone exceeds the
     * budget.
     *
     * @param text      source text
     * @param maxTokens token budget of a single chunk
     * @return atomic segments in reading order, each carrying its start offset in {@code text}
     */
    private List<Segment> toSegments(String text, int maxTokens) {
        List<Segment> raw = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int segmentStart = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            current.append(c);
            if (SEGMENT_TERMINATORS.indexOf(c) >= 0) {
                raw.add(new Segment(current.toString(), segmentStart));
                current.setLength(0);
                segmentStart = i + 1;
            }
        }
        if (current.length() > 0) {
            raw.add(new Segment(current.toString(), segmentStart));
        }

        List<Segment> segments = new ArrayList<>(raw.size());
        for (Segment segment : raw) {
            if (segment.text().isEmpty()) {
                continue;
            }
            if (tokenEstimator.estimate(segment.text()) <= maxTokens) {
                segments.add(segment);
                continue;
            }
            segments.addAll(hardCut(segment, maxTokens));
        }
        return segments;
    }

    /**
     * Cuts an oversized segment on character positions matching the token budget.
     *
     * @param segment   oversized segment
     * @param maxTokens token budget of a single chunk
     * @return pieces that individually fit into the budget, each keeping its source position
     */
    private List<Segment> hardCut(Segment segment, int maxTokens) {
        List<Segment> pieces = new ArrayList<>();
        String remaining = segment.text();
        int start = segment.start();
        while (!remaining.isEmpty()) {
            int cut = tokenEstimator.prefixLengthWithinBudget(remaining, maxTokens);
            if (cut <= 0) {
                cut = 1;
            }
            pieces.add(new Segment(remaining.substring(0, cut), start));
            start += cut;
            remaining = remaining.substring(cut);
        }
        return pieces;
    }

    /**
     * Selects the trailing segments that fit into the overlap budget.
     *
     * @param buffer        segments of the chunk that was just emitted
     * @param overlapTokens overlap budget in estimated tokens
     * @return segments replayed at the head of the next chunk
     */
    private List<Segment> tailOverlap(List<Segment> buffer, int overlapTokens) {
        List<Segment> overlap = new ArrayList<>();
        if (overlapTokens <= 0) {
            return overlap;
        }
        int tokens = 0;
        for (int i = buffer.size() - 1; i >= 0; i--) {
            int segmentTokens = tokenEstimator.estimate(buffer.get(i).text());
            if (tokens + segmentTokens > overlapTokens) {
                break;
            }
            overlap.add(0, buffer.get(i));
            tokens += segmentTokens;
        }
        return overlap;
    }

    private int totalTokens(List<Segment> segments) {
        int total = 0;
        for (Segment segment : segments) {
            total += tokenEstimator.estimate(segment.text());
        }
        return total;
    }

    /**
     * Builds one chunk out of the packed segments and derives its offsets.
     *
     * <p>The trim is mirrored on the offsets rather than applied to the text alone, so
     * {@code source.substring(startOffset, endOffset)} is character for character the chunk content. A
     * blank packing reports no offsets, because an empty text has no position worth naming.
     *
     * @param seq      order of the chunk inside the source text
     * @param segments contiguous run of segments the chunk is made of
     * @return chunk with its content, token estimate and source offsets
     */
    private SplitChunk newChunk(int seq, List<Segment> segments) {
        StringBuilder joined = new StringBuilder();
        for (Segment segment : segments) {
            joined.append(segment.text());
        }
        int begin = 0;
        int end = joined.length();
        while (begin < end && joined.charAt(begin) <= TRIM_BOUNDARY) {
            begin++;
        }
        while (begin < end && joined.charAt(end - 1) <= TRIM_BOUNDARY) {
            end--;
        }
        String content = joined.substring(begin, end);
        if (content.isEmpty()) {
            return new SplitChunk(seq, content, tokenEstimator.estimate(content));
        }
        int start = segments.get(0).start() + begin;
        return new SplitChunk(seq, content, tokenEstimator.estimate(content), null,
                start, start + content.length());
    }

    /**
     * One atomic segment together with where it starts in the source text.
     *
     * @param text  segment text, always a verbatim slice of the source
     * @param start start offset of {@code text} inside the source
     */
    private record Segment(String text, int start) {
    }
}
