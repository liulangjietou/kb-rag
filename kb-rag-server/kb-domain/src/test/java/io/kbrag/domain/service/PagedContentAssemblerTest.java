package io.kbrag.domain.service;

import io.kbrag.domain.model.CleanRules;
import io.kbrag.domain.model.PageRange;
import io.kbrag.domain.model.PagedContent;
import io.kbrag.domain.model.ParsedDocument;
import io.kbrag.domain.model.ProxiedContent;
import io.kbrag.domain.model.SplitChunk;
import io.kbrag.domain.model.SplitParams;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers what the page strategy is cut from.
 *
 * <p>The defect these cases pin down: the page strategy used to read {@code pages[].text} straight out
 * of the parse artifact, which is the text as the parser produced it. Everything the cleaning stage does
 * - the running headers, the watermarks, the operator replacements and the masking - was applied to the
 * merged markdown only, so a page split knowledge base indexed unmasked personal data, and the image
 * placeholders were never resolved on that route either, leaving every page chunk without an image.
 *
 * <p>The two properties asserted here are what make the boundaries trustworthy: with no rule active the
 * assembly reproduces the parser's own merged markdown character for character, and with rules active
 * every boundary still addresses exactly its own page inside the cleaned text.
 *
 * @author owlzhangfq@gmail.com
 */
class PagedContentAssemblerTest {

    private final PagedContentAssembler assembler = new PagedContentAssembler(
            new DocumentCleaner(new HeaderFooterDetector(), new TextDesensitizer()),
            new ImagePlaceholderResolver());

    @Test
    void shouldMaskPersonalDataOnEveryPage() {
        CleanRules rules = new CleanRules();
        rules.getDesensitize().setEnabled(true);

        PagedContent paged = assembler.assemble(List.of(
                page(1, "## Page 1\n\n联系人 13800138000"),
                page(2, "## Page 2\n\n备用 13900139000")), rules, Map.of());

        String markdown = paged.getContent().getMarkdown();
        assertFalse(markdown.contains("13800138000"), "page one must not reach the index unmasked");
        assertFalse(markdown.contains("13900139000"), "page two must not reach the index unmasked");
        assertTrue(markdown.contains("138****8000"));
        assertEquals(2, paged.getPageRanges().size());
    }

    @Test
    void shouldRemoveTheRunningHeaderFromEveryPage() {
        // Furniture is detected once over the whole document: a header repeats across pages by
        // definition, so a per page detection would find nothing and delete nothing.
        CleanRules rules = new CleanRules();
        rules.setStripHeaderFooter(true);

        PagedContent paged = assembler.assemble(List.of(
                page(1, "ACME Internal\n第一页正文"),
                page(2, "ACME Internal\n第二页正文"),
                page(3, "ACME Internal\n第三页正文")), rules, Map.of());

        assertFalse(paged.getContent().getMarkdown().contains("ACME Internal"));
        assertTrue(paged.getContent().getMarkdown().contains("第二页正文"));
    }

    @Test
    void shouldSpliceImageProxiesIntoThePageThatCarriesThem() {
        Map<String, ImagePlaceholderResolver.ProxyTarget> proxies = new LinkedHashMap<>();
        proxies.put("img-a", new ImagePlaceholderResolver.ProxyTarget("IMG_A", "kb/a.png", "一张柱状图"));

        PagedContent paged = assembler.assemble(List.of(
                page(1, "## Page 1\n\n第一页正文"),
                page(2, "## Page 2\n\n第二页正文\n\n[[IMAGE:img-a]]")), CleanRules.defaults(), proxies);

        assertEquals(1, paged.getContent().getPlacements().size());
        ProxiedContent.ImagePlacement placement = paged.getContent().getPlacements().get(0);
        PageRange second = paged.getPageRanges().get(1);
        // The offset is what later links a chunk to its image. Landing inside page two's range is the
        // whole point: on the old route the placeholder never reached this text at all.
        assertTrue(placement.getStart() >= second.getStart() && placement.getEnd() <= second.getEnd(),
                "the proxy must sit inside the page that carried the placeholder");
        assertTrue(paged.getContent().getMarkdown().contains("一张柱状图"));
    }

    @Test
    void shouldReproduceTheParserMergedMarkdownWhenNoRuleIsActive() {
        // The parser joins its per page markdown with a blank line, and the assembly mirrors that. With
        // no rule active the page strategy therefore cuts exactly the characters the fixed length
        // strategy would have been given - the two routes cannot drift apart on an unconfigured base.
        String first = "## Page 1\n\n第一页正文";
        String second = "## Page 2\n\n第二页正文";

        PagedContent paged = assembler.assemble(List.of(page(1, first), page(2, second)),
                CleanRules.defaults(), Map.of());

        assertEquals(first + "\n\n" + second, paged.getContent().getMarkdown());
    }

    @Test
    void shouldAddressEachPageWithItsOwnRange() {
        PagedContent paged = assembler.assemble(List.of(
                page(1, "第一页正文"), page(2, "第二页正文"), page(3, "第三页正文")),
                CleanRules.defaults(), Map.of());

        String markdown = paged.getContent().getMarkdown();
        List<PageRange> ranges = paged.getPageRanges();
        assertEquals(3, ranges.size());
        assertEquals("第一页正文", markdown.substring(ranges.get(0).getStart(), ranges.get(0).getEnd()));
        assertEquals("第二页正文", markdown.substring(ranges.get(1).getStart(), ranges.get(1).getEnd()));
        assertEquals(3, ranges.get(2).getPageNo());
    }

    @Test
    void shouldFallBackToThePagePlainTextWhenTheParserReportedNoMarkdown() {
        // Artifacts written before the parser carried per page markdown still have to index: the page
        // yields its plain text, just without the placeholders it never held.
        ParsedDocument.ParsedPage legacy = new ParsedDocument.ParsedPage();
        legacy.setPageNo(1);
        legacy.setText("旧版解析产物只有纯文本");

        PagedContent paged = assembler.assemble(List.of(legacy), CleanRules.defaults(), Map.of());

        assertEquals("旧版解析产物只有纯文本", paged.getContent().getMarkdown());
        assertEquals(1, paged.getPageRanges().size());
    }

    @Test
    void shouldSkipAPageCleaningLeftBlank() {
        // A page whose whole content was furniture contributes no chunk, so it gets no range either: a
        // zero width range would only make the splitter emit nothing for a page number with no content.
        CleanRules rules = new CleanRules();
        rules.setStripHeaderFooter(true);

        PagedContent paged = assembler.assemble(List.of(
                page(1, "ACME Internal"),
                page(2, "ACME Internal"),
                page(3, "ACME Internal\n第三页正文")), rules, Map.of());

        PageRange survivor = paged.getPageRanges().get(0);
        assertEquals(1, paged.getPageRanges().size());
        assertEquals(3, survivor.getPageNo());
        // A skipped page must not leave a gap the following offsets are still counting: the surviving
        // range has to address its own text and nothing else.
        assertEquals("第三页正文", paged.getContent().getMarkdown()
                .substring(survivor.getStart(), survivor.getEnd()));
    }

    @Test
    void shouldLetAPageChunkClaimTheImageItContains() {
        // The three stages the page route is made of, run end to end: assemble, cut on the boundaries,
        // then link each chunk to the images its range covers. This is the defect in its most visible
        // form - on the old route the placeholder never reached the text the strategy cut, so every page
        // chunk of every document indexed with an empty image_urls.
        Map<String, ImagePlaceholderResolver.ProxyTarget> proxies = new LinkedHashMap<>();
        proxies.put("img-a", new ImagePlaceholderResolver.ProxyTarget("IMG_A", "kb/a.png", "一张柱状图"));

        PagedContent paged = assembler.assemble(List.of(
                page(1, "## Page 1\n\n第一页正文"),
                page(2, "## Page 2\n\n第二页正文\n\n[[IMAGE:img-a]]")), CleanRules.defaults(), proxies);

        SimpleTokenEstimator estimator = new SimpleTokenEstimator();
        List<SplitChunk> chunks = new PageSplitter(new FixedLengthTextSplitter(estimator), estimator)
                .split(paged.getContent().getMarkdown(), paged.getPageRanges(), SplitParams.of(600, 0));
        Map<Integer, List<String>> imagesByChunk = new ImageChunkLinker().link(
                paged.getContent().getMarkdown(), paged.getContent().getPlacements(),
                chunks.stream().map(SplitChunk::getContent).toList());

        assertEquals(2, chunks.size());
        assertNull(imagesByChunk.get(0), "the page without an image claims none");
        assertEquals(List.of("kb/a.png"), imagesByChunk.get(1));
    }

    @Test
    void shouldYieldAnEmptyDocumentWhenThereAreNoPages() {
        PagedContent paged = assembler.assemble(List.of(), CleanRules.defaults(), Map.of());

        assertEquals("", paged.getContent().getMarkdown());
        assertTrue(paged.getPageRanges().isEmpty());
    }

    private ParsedDocument.ParsedPage page(int pageNo, String markdown) {
        ParsedDocument.ParsedPage page = new ParsedDocument.ParsedPage();
        page.setPageNo(pageNo);
        // The plain text is what the furniture detection compares, the markdown is what gets cut; the
        // parser reports both, and the header lines have to appear in both to be detected.
        page.setText(markdown);
        page.setMarkdown(markdown);
        return page;
    }
}
