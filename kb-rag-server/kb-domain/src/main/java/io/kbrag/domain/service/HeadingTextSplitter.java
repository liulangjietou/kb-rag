package io.kbrag.domain.service;

import io.kbrag.domain.model.SplitChunk;
import io.kbrag.domain.model.SplitParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Heading splitting strategy, the M14 contract section 4: opens a new block on every markdown heading
 * up to a configured depth, the heading line staying with the content it introduces.
 *
 * <p>A document with no heading at the configured depth degrades to {@link FixedLengthTextSplitter}
 * over the whole text: a strategy that produced one enormous chunk for a heading free document would
 * be worse than the default, not a specialisation of it. Packing and the over long section fallback
 * are delegated to {@link FixedLengthTextSplitter#packBlocks}.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HeadingTextSplitter implements TextSplitter {

    /** Strategy code persisted in the split fingerprint. */
    public static final String STRATEGY_CODE = "heading";

    /** Shallowest heading depth an operator may configure. */
    public static final int MIN_HEADING_LEVEL = 1;

    /** Deepest heading depth an operator may configure, markdown stops at six. */
    public static final int MAX_HEADING_LEVEL = 6;

    /** Depth used when the configuration names none, the document sections rather than its subsections. */
    public static final int DEFAULT_HEADING_LEVEL = 2;

    private final FixedLengthTextSplitter fixedLengthTextSplitter;

    @Override
    public String strategy() {
        return STRATEGY_CODE;
    }

    @Override
    public List<SplitChunk> split(String text, SplitParams params) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        int level = resolveLevel(params.getHeadingLevel());
        Pattern headingPattern = Pattern.compile("^#{1," + level + "}\\s");
        List<String> blocks = toBlocks(text, headingPattern);
        if (blocks.isEmpty()) {
            return fixedLengthTextSplitter.split(text, params);
        }
        return fixedLengthTextSplitter.packBlocks(blocks, params);
    }

    private int resolveLevel(int configured) {
        if (configured < MIN_HEADING_LEVEL || configured > MAX_HEADING_LEVEL) {
            return DEFAULT_HEADING_LEVEL;
        }
        return configured;
    }

    /**
     * Groups the lines into blocks, each starting at a heading line and running until the next one.
     *
     * <p>Text before the first heading is kept as its own leading block so an introduction is never
     * dropped. An empty list means the document carried no heading at this depth, which is the caller's
     * signal to fall back to the fixed length strategy.
     *
     * @param text           source text
     * @param headingPattern compiled heading matcher
     * @return blocks in reading order, empty when no heading was found
     */
    private List<String> toBlocks(String text, Pattern headingPattern) {
        String[] lines = text.split("\n", -1);
        List<String> blocks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean headingSeen = false;
        for (String line : lines) {
            if (headingPattern.matcher(line).find()) {
                if (current.length() > 0) {
                    blocks.add(current.toString());
                    current.setLength(0);
                }
                headingSeen = true;
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(line);
        }
        if (!headingSeen) {
            return List.of();
        }
        if (current.length() > 0) {
            blocks.add(current.toString());
        }
        return blocks;
    }
}
