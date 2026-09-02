package io.kbrag.parser.parser;

import io.kbrag.parser.config.ParserConstants;
import io.kbrag.parser.config.ParserProperties;
import io.kbrag.parser.model.ImageAsset;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * Accumulates image assets for one document, enforcing the per-document count cap and the per-image
 * byte cap (M3-CONTRACTS.md §2.1).
 *
 * <p>Centralizing both caps here means every parser enforces them the same way instead of duplicating
 * guard logic, and keeps image id assignment sequential and consistent regardless of kind. One
 * instance is created per parse call and discarded afterwards; it is not shared across documents or
 * threads.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
public class ImageAssetCollector {

    private final int maxImages;
    private final long maxImageBytes;
    private final List<ImageAsset> images = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private int nextSeq = 1;

    public ImageAssetCollector(ParserProperties properties) {
        this(properties.getMaxImagesPerDoc(), properties.getMaxImageBytes());
    }

    public ImageAssetCollector(int maxImages, long maxImageBytes) {
        this.maxImages = maxImages;
        this.maxImageBytes = maxImageBytes;
    }

    public List<ImageAsset> getImages() {
        return Collections.unmodifiableList(images);
    }

    public List<String> getWarnings() {
        return Collections.unmodifiableList(warnings);
    }

    /**
     * Tells whether one more image still fits under the per-document count cap, recording the same
     * skip warning {@link #tryAdd} would have recorded when it does not.
     *
     * <p>Exposed so a caller can avoid <i>producing</i> bytes this collector would only throw away:
     * rasterizing one pdf page costs on the order of 100ms, and a long scanned document reaches the cap
     * far before its last page, so asking first turns those renders into no-ops. Call it at most once
     * per candidate image - each call over the cap appends one warning.
     *
     * @param pageNo page the candidate image belongs to
     * @param kind   {@link ImageKind} value
     * @return true when the image would be accepted by the count cap
     */
    public boolean hasCapacity(int pageNo, String kind) {
        if (images.size() < maxImages) {
            return true;
        }
        log.info("image skipped, reason=doc_image_count_limit, pageNo={}, kind={}, limit={}",
                pageNo, kind, maxImages);
        warnings.add("image on page " + pageNo + " (" + kind
                + ") skipped: document image count reached limit " + maxImages);
        return false;
    }

    /**
     * Adds one image if it is under both caps.
     *
     * <p>A skip never throws: the containing document stays fully parseable, which is what
     * M3-CONTRACTS.md §2.1 asks for ("超限跳过并写 warnings，不失败整篇").
     *
     * @param pageNo    page the image belongs to
     * @param kind      {@link ImageKind} value
     * @param mediaType MIME type of the bytes
     * @param rawBytes  the image itself
     * @return the assigned image id, or null when the image was skipped
     */
    public String tryAdd(int pageNo, String kind, String mediaType, byte[] rawBytes) {
        if (!hasCapacity(pageNo, kind)) {
            return null;
        }
        int size = rawBytes.length;
        if (size > maxImageBytes) {
            log.info("image skipped, reason=image_bytes_limit, pageNo={}, kind={}, sizeBytes={}, limitBytes={}",
                    pageNo, kind, size, maxImageBytes);
            warnings.add("image on page " + pageNo + " (" + kind + ") skipped: size " + size
                    + " bytes exceeds limit " + maxImageBytes + " bytes");
            return null;
        }
        String imageId = "img_" + nextSeq;
        nextSeq++;
        images.add(ImageAsset.builder()
                .imageId(imageId)
                .pageNo(pageNo)
                .kind(kind)
                .mediaType(mediaType)
                .contentBase64(Base64.getEncoder().encodeToString(rawBytes))
                .build());
        return imageId;
    }

    /**
     * Renders the fixed {@code [[IMAGE:{image_id}]]} markdown placeholder token.
     *
     * @param imageId the id returned by {@link #tryAdd}
     * @return the placeholder line
     */
    public static String placeholder(String imageId) {
        return String.format(ParserConstants.IMAGE_PLACEHOLDER_FORMAT, imageId);
    }
}
