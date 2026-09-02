package io.kbrag.parser.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single logical page of extracted content.
 *
 * @author owlzhangfq@gmail.com
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageContent {

    /** 1-based page number. */
    private int pageNo;

    /**
     * Extracted plain text of this page. kb-rag-server's header/footer detection compares pages
     * against each other and must keep seeing exactly what was extracted, so this stays free of the
     * page heading and of image placeholders - {@link #markdown} carries those.
     */
    private String text;

    /**
     * This page's slice of {@link ParseData#getMarkdown()}, carrying its heading and its
     * {@code [[IMAGE:{image_id}]]} placeholder lines (M14-CONTRACTS.md §F3).
     *
     * <p>The page-splitting strategy cuts this instead of {@link #text} so it consumes the same
     * cleaned, proxied markdown as every other strategy. Joining every page's slice with a blank line
     * reproduces the document markdown verbatim - the invariant kb-rag-server's page-by-page cleaning
     * depends on. Null in an artifact written before this field existed, where the consumer falls back
     * to {@link #text}.
     */
    private String markdown;

    /**
     * True when this page has no usable text layer and was rendered to a {@code page_render} image
     * instead (M3-CONTRACTS.md §2.1). Only pdf pages can ever be scanned; every other format always
     * reports false.
     */
    private boolean scanned;

    /**
     * Set when this scanned page's text was backfilled by this service's own local OCR fallback
     * (M8-CONTRACTS.md §0.4); null otherwise - OCR disabled, a non-scanned page, or an OCR attempt
     * that timed out or failed and left the page skipped. kb-rag-server does not run its own VLM OCR
     * pass on a page that already carries this marker.
     */
    private String ocrSource;
}
