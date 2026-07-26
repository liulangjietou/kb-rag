package io.kbrag.domain.model;

import lombok.Getter;
import lombok.ToString;

import java.util.List;

/**
 * Markdown whose image placeholders were replaced by textual proxies, together with where each proxy
 * landed.
 *
 * <p>The offsets are what later links a chunk to the images it derives from. Recording them at
 * substitution time is the only place where the correspondence is exact: once the text is split, a
 * proxy is indistinguishable from the prose around it.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@ToString(exclude = "markdown")
public class ProxiedContent {

    /** Markdown with every placeholder resolved. */
    private final String markdown;

    /** Where each image proxy was inserted, in reading order. */
    private final List<ImagePlacement> placements;

    public ProxiedContent(String markdown, List<ImagePlacement> placements) {
        this.markdown = markdown;
        this.placements = placements;
    }

    /**
     * Position of one image proxy inside the resolved markdown.
     */
    @Getter
    @ToString
    public static class ImagePlacement {

        /** Business id of the image asset. */
        private final String imageId;

        /** Object storage key of the image binary. */
        private final String objectKey;

        /** Inclusive start offset of the inserted proxy block. */
        private final int start;

        /** Exclusive end offset of the inserted proxy block. */
        private final int end;

        public ImagePlacement(String imageId, String objectKey, int start, int end) {
            this.imageId = imageId;
            this.objectKey = objectKey;
            this.start = start;
            this.end = end;
        }

        /**
         * Tells whether this proxy overlaps a text range.
         *
         * @param rangeStart inclusive start of the range
         * @param rangeEnd   exclusive end of the range
         * @return {@code true} when the two ranges intersect
         */
        public boolean overlaps(int rangeStart, int rangeEnd) {
            return start < rangeEnd && rangeStart < end;
        }
    }
}
