package io.kbrag.domain.service;

import io.kbrag.domain.model.CleanRules;
import io.kbrag.domain.model.ParsedDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Applies the cleaning rules in the one order the contract fixes.
 *
 * <pre>
 * strip header and footer -&gt; strip watermarks -&gt; regular expression replacements -&gt; mask personal data
 * </pre>
 *
 * <p><b>Why that order and not another.</b> Header and footer detection compares whole lines against the
 * other pages, so it has to see the text exactly as the parser produced it: any earlier substitution
 * would break the equality it relies on. Watermark stripping comes next because a watermark is noise
 * that no later rule should have to work around. The operator supplied replacements run third so they
 * see a document already free of furniture. Masking is last so it is the final word: a replacement can
 * never expose a value the masking step had already hidden.
 *
 * <p>Each step is independently switchable and a disabled step is a no-op, so a knowledge base that
 * configures nothing gets the untouched parse result back.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentCleaner {

    private static final String LINE_SPLIT = "\\R";
    private static final String LINE_BREAK = "\n";
    private static final String EMPTY = "";

    private final HeaderFooterDetector headerFooterDetector;
    private final TextDesensitizer textDesensitizer;

    /**
     * Cleans a whole document.
     *
     * @param markdown parsed markdown
     * @param pages    per page text, used by the header and footer detection only
     * @param rules    cleaning rules
     * @return cleaned markdown
     */
    public String clean(String markdown, List<ParsedDocument.ParsedPage> pages, CleanRules rules) {
        if (markdown == null || markdown.isEmpty() || rules == null || !rules.anyActive()) {
            return markdown;
        }
        String cleaned = markdown;
        if (rules.isStripHeaderFooter()) {
            cleaned = stripHeaderFooter(cleaned, pages);
        }
        cleaned = stripWatermarks(cleaned, rules);
        cleaned = applyReplacements(cleaned, rules);
        cleaned = textDesensitizer.mask(cleaned, rules.desensitizeOrDisabled());
        return cleaned;
    }

    /**
     * Cleans a document page by page, the M14 contract section 4 page strategy.
     *
     * <p>Exists so the page strategy consumes the same cleaned text every other strategy consumes.
     * Cutting the already cleaned markdown on page boundaries is not an option: cleaning deletes lines
     * and rewrites matches, so an offset taken on the parse artifact no longer addresses the same text
     * afterwards. Cleaning each page separately keeps the boundary exact by construction.
     *
     * <p>The furniture lines are detected once over the whole document and then removed from every
     * page: a header is by definition a line that repeats across pages, so detecting it inside a single
     * page would find nothing.
     *
     * <p>The one behavioural difference with {@link #clean}: a watermark or a replacement pattern can no
     * longer match across a page boundary. That is the more faithful reading of rules written against
     * the furniture of a page, and with no rule active both routes produce the same text.
     *
     * @param pages per page text and markdown
     * @param rules cleaning rules
     * @return cleaned markdown per page, in page order and of the same size as {@code pages}
     */
    public List<String> cleanPages(List<ParsedDocument.ParsedPage> pages, CleanRules rules) {
        List<ParsedDocument.ParsedPage> source = pages == null ? List.of() : pages;
        List<String> cleaned = new ArrayList<>(source.size());
        if (rules == null || !rules.anyActive()) {
            for (ParsedDocument.ParsedPage page : source) {
                cleaned.add(page.markdownOrText());
            }
            return cleaned;
        }
        Set<String> furniture = rules.isStripHeaderFooter() ? headerFooterDetector.detect(source) : Set.of();
        int removed = 0;
        for (ParsedDocument.ParsedPage page : source) {
            StripResult stripped = stripLines(page.markdownOrText(), furniture);
            removed += stripped.removed();
            String text = stripWatermarks(stripped.text(), rules);
            text = applyReplacements(text, rules);
            cleaned.add(textDesensitizer.mask(text, rules.desensitizeOrDisabled()));
        }
        log.info("pages cleaned, pages={}, removedLines={}, patterns={}",
                source.size(), removed, furniture.size());
        return cleaned;
    }

    /**
     * Cleans a text fragment that has no page structure, such as the textual proxy of an image or a
     * rendered chat window.
     *
     * <p>The header and footer step is skipped by construction: there are no pages to compare against,
     * and applying the document level furniture list to a fragment would delete a line that merely
     * happens to look like a header.
     *
     * @param text  source fragment
     * @param rules cleaning rules
     * @return cleaned fragment
     */
    public String cleanFragment(String text, CleanRules rules) {
        if (text == null || text.isEmpty() || rules == null || !rules.anyActive()) {
            return text;
        }
        String cleaned = stripWatermarks(text, rules);
        cleaned = applyReplacements(cleaned, rules);
        return textDesensitizer.mask(cleaned, rules.desensitizeOrDisabled());
    }

    /**
     * Removes the lines that repeat at the edge of most pages.
     *
     * @param markdown parsed markdown
     * @param pages    per page text
     * @return markdown without the furniture lines
     */
    private String stripHeaderFooter(String markdown, List<ParsedDocument.ParsedPage> pages) {
        Set<String> furniture = headerFooterDetector.detect(pages);
        StripResult stripped = stripLines(markdown, furniture);
        log.info("header footer lines removed, removedLines={}, patterns={}",
                stripped.removed(), furniture.size());
        return stripped.text();
    }

    /**
     * Drops every line the furniture list names.
     *
     * @param text      source text
     * @param furniture normalised furniture lines, empty leaves the text untouched
     * @return remaining text and how many lines were dropped
     */
    private StripResult stripLines(String text, Set<String> furniture) {
        if (CollectionUtils.isEmpty(furniture)) {
            return new StripResult(text, 0);
        }
        String[] lines = text.split(LINE_SPLIT, -1);
        List<String> kept = new ArrayList<>(lines.length);
        int removed = 0;
        for (String line : lines) {
            if (furniture.contains(headerFooterDetector.normalise(line))) {
                removed++;
                continue;
            }
            kept.add(line);
        }
        return new StripResult(String.join(LINE_BREAK, kept), removed);
    }

    /**
     * Outcome of the furniture removal, so both the whole document route and the page by page route
     * can report one aggregated count instead of one line per page.
     *
     * @param text    remaining text
     * @param removed number of dropped lines
     */
    private record StripResult(String text, int removed) {
    }

    /**
     * Deletes every watermark match.
     *
     * <p>An invalid pattern is reported and skipped rather than failing the document: the rule was typed
     * by an operator into a text field, and one bad expression must not make a whole knowledge base
     * unindexable.
     *
     * @param text  source text
     * @param rules cleaning rules
     * @return text without the watermark matches
     */
    private String stripWatermarks(String text, CleanRules rules) {
        String cleaned = text;
        for (String pattern : rules.watermarkPatterns()) {
            if (pattern == null || pattern.isBlank()) {
                continue;
            }
            cleaned = replaceSafely(cleaned, pattern, EMPTY);
        }
        return cleaned;
    }

    /**
     * Applies the operator supplied replacements in declaration order.
     *
     * @param text  source text
     * @param rules cleaning rules
     * @return replaced text
     */
    private String applyReplacements(String text, CleanRules rules) {
        String cleaned = text;
        for (CleanRules.RegexReplacement replacement : rules.replacements()) {
            if (replacement == null || replacement.getPattern() == null || replacement.getPattern().isBlank()) {
                continue;
            }
            String target = replacement.getReplacement() == null ? EMPTY : replacement.getReplacement();
            cleaned = replaceSafely(cleaned, replacement.getPattern(), target);
        }
        return cleaned;
    }

    private String replaceSafely(String text, String pattern, String replacement) {
        try {
            return Pattern.compile(pattern).matcher(text)
                    .replaceAll(java.util.regex.Matcher.quoteReplacement(replacement));
        } catch (PatternSyntaxException e) {
            log.info("skip invalid cleaning pattern, pattern={}, reason={}", pattern, e.getDescription());
            return text;
        }
    }
}
