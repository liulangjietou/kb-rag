package io.kbrag.domain.model;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.util.List;

/**
 * Parser service response: the merged markdown plus the per page text used for page anchoring.
 */
@Getter
@Builder
@ToString(exclude = "markdown")
public class ParsedDocument {

    /** Whole document rendered as markdown. */
    private final String markdown;

    /** Per page text, empty for formats without a page concept. */
    private final List<ParsedPage> pages;

    /** Object keys of the extracted images, empty in M1 when the parser extracts none. */
    private final List<String> images;

    /**
     * One page of the parsed document.
     */
    @Getter
    @Builder
    @ToString(exclude = "text")
    public static class ParsedPage {

        /** One based page number. */
        private final int pageNo;

        /** Page text. */
        private final String text;
    }
}
