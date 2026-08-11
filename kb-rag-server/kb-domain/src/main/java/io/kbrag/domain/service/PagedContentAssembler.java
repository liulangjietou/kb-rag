package io.kbrag.domain.service;

import io.kbrag.domain.model.CleanRules;
import io.kbrag.domain.model.PageRange;
import io.kbrag.domain.model.PagedContent;
import io.kbrag.domain.model.ParsedDocument;
import io.kbrag.domain.model.ProxiedContent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds the cleaned document of a page split knowledge base, keeping the page boundaries exact.
 *
 * <p><b>Why the document is assembled instead of being annotated afterwards.</b> The page strategy
 * needs two things at once: the text every other strategy is cut from - cleaned, masked, with the image
 * proxies spliced in - and the offset where each page starts inside it. Cleaning the whole document
 * first and then looking the pages up in the result cannot deliver the second: the header removal
 * deletes lines and the replacements rewrite matches, so the parse artifact's pages no longer occur
 * verbatim in the cleaned text. Cleaning page by page and concatenating gives both for free, because
 * the offset of a page is simply where it was appended.
 *
 * <p>The pages are joined with the separator the parser itself used to merge them, so a document with
 * no active cleaning rule assembles back into exactly {@code ParsedDocument.markdown} - the page
 * strategy then cuts the very same characters the fixed length strategy would have.
 *
 * <p>A page left blank by cleaning is skipped rather than assigned an empty range: it contributes no
 * chunk, and a zero width range would only make the splitter emit nothing for a page number that no
 * longer has content.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PagedContentAssembler {

    /** Separator the parser joins its per page markdown with, mirrored so the assembly is lossless. */
    private static final String PAGE_SEPARATOR = "\n\n";

    private final DocumentCleaner documentCleaner;
    private final ImagePlaceholderResolver placeholderResolver;

    /**
     * Cleans every page, splices the image proxies into it and concatenates the result.
     *
     * @param pages   parse artifact pages, empty yields an empty document with no ranges
     * @param rules   cleaning rules
     * @param proxies textual proxy per parser supplied image id
     * @return cleaned markdown, the image placements inside it and the page boundaries
     */
    public PagedContent assemble(List<ParsedDocument.ParsedPage> pages, CleanRules rules,
                                 Map<String, ImagePlaceholderResolver.ProxyTarget> proxies) {
        List<ParsedDocument.ParsedPage> source = pages == null ? List.of() : pages;
        List<String> cleaned = documentCleaner.cleanPages(source, rules);
        StringBuilder markdown = new StringBuilder();
        List<PageRange> ranges = new ArrayList<>(source.size());
        List<ProxiedContent.ImagePlacement> placements = new ArrayList<>();
        for (int index = 0; index < source.size(); index++) {
            ProxiedContent page = placeholderResolver.resolve(cleaned.get(index), proxies);
            if (page.getMarkdown() == null || page.getMarkdown().isBlank()) {
                continue;
            }
            if (markdown.length() > 0) {
                markdown.append(PAGE_SEPARATOR);
            }
            int offset = markdown.length();
            markdown.append(page.getMarkdown());
            ranges.add(new PageRange(source.get(index).getPageNo(), offset, markdown.length()));
            for (ProxiedContent.ImagePlacement placement : page.getPlacements()) {
                placements.add(new ProxiedContent.ImagePlacement(placement.getImageId(),
                        placement.getObjectKey(), placement.getStart() + offset,
                        placement.getEnd() + offset));
            }
        }
        log.info("paged content assembled, pages={}, ranges={}, images={}",
                source.size(), ranges.size(), placements.size());
        return new PagedContent(new ProxiedContent(markdown.toString(), placements), ranges);
    }
}
