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
 * Separator splitting strategy, the M14 contract section 4: cuts the text on a literal or regular
 * expression delimiter and packs the resulting blocks up to the token budget.
 *
 * <p>The delimiter itself is not kept - a paragraph break or a row marker carries no retrieval value
 * once it has done its splitting job. Packing and the over long block fallback are delegated to
 * {@link FixedLengthTextSplitter#packBlocks}, so this strategy owns only the segmentation and the
 * fixed length one owns the token accounting exactly once.
 *
 * <p>The configuration gate compiles a regular expression delimiter at write time, so a pattern that
 * reaches here can be trusted; a stored configuration that predates that gate and no longer compiles
 * degrades to splitting the whole text as one block rather than failing the build, the same direction
 * the metadata rule extractor takes.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeparatorTextSplitter implements TextSplitter {

    /** Strategy code persisted in the split fingerprint. */
    public static final String STRATEGY_CODE = "separator";

    /** Delimiter used when the configuration names none, a blank line between paragraphs. */
    public static final String DEFAULT_SEPARATOR = "\n\n";

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
        String separator = params.getSeparator() == null || params.getSeparator().isEmpty()
                ? DEFAULT_SEPARATOR : params.getSeparator();
        List<String> blocks = toBlocks(text, separator, params.isSeparatorIsRegex());
        return fixedLengthTextSplitter.packBlocks(blocks, params);
    }

    /**
     * Cuts the text on the delimiter, dropping empty blocks so a run of separators does not produce
     * empty chunks.
     *
     * @param text      source text
     * @param separator delimiter, literal or regular expression
     * @param isRegex   whether {@code separator} is a regular expression
     * @return blocks in reading order
     */
    private List<String> toBlocks(String text, String separator, boolean isRegex) {
        Pattern pattern = compile(separator, isRegex);
        List<String> blocks = new ArrayList<>();
        for (String block : pattern.split(text, -1)) {
            if (!block.isBlank()) {
                blocks.add(block);
            }
        }
        return blocks;
    }

    private Pattern compile(String separator, boolean isRegex) {
        if (!isRegex) {
            return Pattern.compile(Pattern.quote(separator));
        }
        try {
            return Pattern.compile(separator);
        } catch (Exception e) {
            log.info("separator strategy pattern no longer compiles, splitting as one block, pattern={}",
                    separator);
            return Pattern.compile(Pattern.quote(separator));
        }
    }
}
