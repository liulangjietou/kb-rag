package io.kbrag.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

/**
 * Parser service response: the merged markdown, the per page text and the extracted images.
 *
 * <p>The type is serialised into object storage as the reusable parse artifact, so it carries Jackson
 * annotations and setters as well as the builder: a rebuild reads it back instead of paying for a
 * second parse, and the per page text is what the header and footer detection needs.
 *
 * <p>The image binaries deliberately do not survive serialisation. They are uploaded to object storage
 * during the image stage and the asset rows point at them, so keeping the base64 payload in the
 * artifact would store every image twice.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = "markdown")
@JsonIgnoreProperties(ignoreUnknown = true)
public class ParsedDocument {

    /** Whole document rendered as markdown, image positions marked by placeholders. */
    @JsonProperty("markdown")
    private String markdown;

    /** Per page text, empty for formats without a page concept. */
    @JsonProperty("pages")
    @Builder.Default
    private List<ParsedPage> pages = new ArrayList<>();

    /** Images the parser extracted, never serialised into the parse artifact. */
    @JsonIgnore
    @Builder.Default
    private List<ParsedImage> images = new ArrayList<>();

    /** Non fatal parser findings, for example images skipped because of a size limit. */
    @JsonProperty("warnings")
    @Builder.Default
    private List<String> warnings = new ArrayList<>();

    /**
     * Markdown, never {@code null}.
     *
     * @return document text
     */
    public String markdownOrEmpty() {
        return markdown == null ? "" : markdown;
    }

    /**
     * Pages, never {@code null}.
     *
     * @return per page text
     */
    public List<ParsedPage> pagesOrEmpty() {
        return pages == null ? List.of() : pages;
    }

    /**
     * Images, never {@code null}.
     *
     * @return extracted images
     */
    public List<ParsedImage> imagesOrEmpty() {
        return images == null ? List.of() : images;
    }

    /**
     * Warnings, never {@code null}.
     *
     * @return parser warnings
     */
    public List<String> warningsOrEmpty() {
        return warnings == null ? List.of() : warnings;
    }

    /**
     * One page of the parsed document.
     */
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @ToString(exclude = "text")
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ParsedPage {

        /** One based page number. */
        @JsonProperty("page_no")
        private int pageNo;

        /** Page text. */
        @JsonProperty("text")
        private String text;

        /** Set when the page carries no usable text layer and was rendered as an image instead. */
        @JsonProperty("scanned")
        private boolean scanned;
    }

    /**
     * One image extracted by the parser, carrying its binary until the image stage stores it.
     */
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @ToString(exclude = "content")
    public static class ParsedImage {

        /** Identifier used inside the markdown placeholder, unique within this document only. */
        private String imageId;

        /** One based page the image was found on, {@code null} for formats without pages. */
        private Integer pageNo;

        /** Origin of the image. */
        private String kind;

        /** MIME type of the binary. */
        private String mediaType;

        /** Raw image bytes. */
        private byte[] content;
    }
}
