package io.kbrag.domain.model;

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
 * What a document would be indexed as, stored in object storage while it waits for a human decision.
 *
 * <p>The confirmation splits exactly this text. Re-deriving it at confirmation time would let a
 * configuration change between the preview and the click alter what actually lands in the index, so the
 * artifact is the contract between the two calls, including the image placements and the ad hoc cleaning
 * rules a re-parse was asked to try.
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
public class ParsePreview {

    /** Cleaned text with the image proxies already spliced in. */
    @JsonProperty("markdown")
    private String markdown;

    /** Per page text of the parse result. */
    @JsonProperty("pages")
    @Builder.Default
    private List<ParsedDocument.ParsedPage> pages = new ArrayList<>();

    /** Images of this version and their textual proxies. */
    @JsonProperty("images")
    @Builder.Default
    private List<PreviewImage> images = new ArrayList<>();

    /** Where each image proxy sits inside {@link #markdown}. */
    @JsonProperty("placements")
    @Builder.Default
    private List<PreviewPlacement> placements = new ArrayList<>();

    /** Non fatal findings of the parse and image stages. */
    @JsonProperty("warnings")
    @Builder.Default
    private List<String> warnings = new ArrayList<>();

    /**
     * Cleaning rules a re-parse was asked to try, {@code null} when the knowledge base rules were used.
     *
     * <p>Carried so the confirmation is consistent with what was shown without writing an experimental
     * rule set into the knowledge base configuration.
     */
    @JsonProperty("clean_rules_override")
    private CleanRules cleanRulesOverride;

    /**
     * Images, never {@code null}.
     *
     * <p>The builder defaults do not apply to the no-arguments constructor Jackson uses, so an artifact
     * written before a field existed deserialises it as {@code null}; every reader goes through these
     * accessors rather than the raw getters.
     *
     * @return preview images
     */
    public List<PreviewImage> imagesOrEmpty() {
        return images == null ? List.of() : images;
    }

    /**
     * Pages, never {@code null}.
     *
     * @return per page text
     */
    public List<ParsedDocument.ParsedPage> pagesOrEmpty() {
        return pages == null ? List.of() : pages;
    }

    /**
     * Warnings, never {@code null}.
     *
     * @return non fatal findings
     */
    public List<String> warningsOrEmpty() {
        return warnings == null ? List.of() : warnings;
    }

    /**
     * Rebuilds the substitution result the splitter needs.
     *
     * @return markdown together with the image placements
     */
    public ProxiedContent toProxiedContent() {
        List<ProxiedContent.ImagePlacement> resolved = new ArrayList<>();
        for (PreviewPlacement placement : placements == null ? List.<PreviewPlacement>of() : placements) {
            resolved.add(new ProxiedContent.ImagePlacement(placement.getImageId(),
                    placement.getObjectKey(), placement.getStart(), placement.getEnd()));
        }
        return new ProxiedContent(markdown == null ? "" : markdown, resolved);
    }

    /**
     * One image of the preview.
     */
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @ToString(exclude = "textProxy")
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PreviewImage {

        /** Business id of the image asset. */
        @JsonProperty("image_id")
        private String imageId;

        /** Object storage key, turned into a pre signed URL by the read model. */
        @JsonProperty("object_key")
        private String objectKey;

        /** One based page the image was found on. */
        @JsonProperty("page_no")
        private Integer pageNo;

        /** Origin of the image. */
        @JsonProperty("kind")
        private String kind;

        /** Textual proxy, {@code null} when the vision stage was skipped or failed. */
        @JsonProperty("text_proxy")
        private String textProxy;

        /** Lifecycle of the textual proxy. */
        @JsonProperty("status")
        private String status;
    }

    /**
     * Position of one image proxy inside the preview text.
     */
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @ToString
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PreviewPlacement {

        /** Business id of the image asset. */
        @JsonProperty("image_id")
        private String imageId;

        /** Object storage key of the image binary. */
        @JsonProperty("object_key")
        private String objectKey;

        /** Inclusive start offset. */
        @JsonProperty("start")
        private int start;

        /** Exclusive end offset. */
        @JsonProperty("end")
        private int end;
    }
}
