package io.kbrag.domain.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

import java.util.List;

/**
 * Cleaned markdown assembled page by page, together with where each page landed in it.
 *
 * <p>Carries the same {@link ProxiedContent} every other strategy is cut from, so the page strategy
 * reads no separate text: the boundaries are an annotation over the one cleaned document, not a second
 * copy of it. That is what keeps the image links, the masking and the chunk contents identical to what
 * the other strategies produce.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@ToString
@RequiredArgsConstructor
public class PagedContent {

    /** Cleaned markdown with the image proxies spliced in. */
    private final ProxiedContent content;

    /** Where each page landed inside {@link #content}, in page order. */
    private final List<PageRange> pageRanges;
}
