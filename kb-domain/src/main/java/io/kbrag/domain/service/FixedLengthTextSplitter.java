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
        List<String> segments = toSegments(text, params.getMaxTokens());

        List<String> buffer = new ArrayList<>();
        int bufferTokens = 0;
        int seq = 0;
        for (String segment : segments) {
            int segmentTokens = tokenEstimator.estimate(segment);
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
     * Breaks the source text into atomic segments, hard cutting any segment that alone exceeds the
     * budget.
     *
     * @param text      source text
     * @param maxTokens token budget of a single chunk
     * @return atomic segments in reading order
     */
    private List<String> toSegments(String text, int maxTokens) {
        List<String> raw = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            current.append(c);
            if (SEGMENT_TERMINATORS.indexOf(c) >= 0) {
                raw.add(current.toString());
                current.setLength(0);
            }
        }
        if (current.length() > 0) {
            raw.add(current.toString());
        }

        List<String> segments = new ArrayList<>(raw.size());
        for (String segment : raw) {
            if (segment.isEmpty()) {
                continue;
            }
            if (tokenEstimator.estimate(segment) <= maxTokens) {
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
     * @return pieces that individually fit into the budget
     */
    private List<String> hardCut(String segment, int maxTokens) {
        List<String> pieces = new ArrayList<>();
        String remaining = segment;
        while (!remaining.isEmpty()) {
            int cut = tokenEstimator.prefixLengthWithinBudget(remaining, maxTokens);
            if (cut <= 0) {
                cut = 1;
            }
            pieces.add(remaining.substring(0, cut));
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
    private List<String> tailOverlap(List<String> buffer, int overlapTokens) {
        List<String> overlap = new ArrayList<>();
        if (overlapTokens <= 0) {
            return overlap;
        }
        int tokens = 0;
        for (int i = buffer.size() - 1; i >= 0; i--) {
            int segmentTokens = tokenEstimator.estimate(buffer.get(i));
            if (tokens + segmentTokens > overlapTokens) {
                break;
            }
            overlap.add(0, buffer.get(i));
            tokens += segmentTokens;
        }
        return overlap;
    }

    private int totalTokens(List<String> segments) {
        int total = 0;
        for (String segment : segments) {
            total += tokenEstimator.estimate(segment);
        }
        return total;
    }

    private SplitChunk newChunk(int seq, List<String> segments) {
        String content = String.join("", segments).trim();
        return new SplitChunk(seq, content, tokenEstimator.estimate(content));
    }
}
