package io.kbrag.app.retrieval;

import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.enums.DegradedReason;
import io.kbrag.domain.model.ImageInput;
import io.kbrag.domain.port.VisionProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Turns the images a caller attached to its question into text and folds that text into the query,
 * requirement section 4.8.
 *
 * <p><b>Base64 only, never a URL.</b> Accepting a link would make the service fetch whatever host the
 * caller names, which is a server side request forgery primitive pointed at the internal network. The
 * bytes travel in the request instead, bounded by a count and two size ceilings that are enforced here and
 * nowhere else - a console may warn earlier, but the authority is this class.
 *
 * <p><b>The concatenation happens before the retrieval pipeline starts, and that ordering is the whole
 * point.</b> The rewrite stage lives inside the pipeline, so appending the image text here is what lets the
 * rewrite see the picture's semantics and resolve "这个零件多少钱" against it. Appending afterwards would
 * rewrite a question the model never saw the subject of.
 *
 * <p><b>Degradation is all or nothing.</b> Zero key, no vision model, a timeout or a provider failure all
 * end the same way: every image is dropped and the search runs on the written question alone, with
 * {@link DegradedReason#IMAGE_UNDERSTANDING_UNAVAILABLE} on the response. Mixing a half understood image
 * set into the query would produce a result nobody can explain, and a caller that cannot see which image
 * was understood cannot judge the answer. A call that carried <em>only</em> images then has nothing left to
 * search for and is rejected outright - returning whatever a blank query recalls would be worse than an
 * error.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageQueryService {

    /** Prefix marking the text an image contributed, so the query stays readable in an audit row. */
    public static final String IMAGE_TEXT_PREFIX = "[图片内容] ";

    /** Separator between the written question and the text of each image. */
    private static final String SEGMENT_SEPARATOR = "\n";

    /** Leading marker of a data URL, tolerated so a console that forgot to strip it is not punished. */
    private static final String DATA_URL_PREFIX = "data:";

    /** Separator between a data URL header and its payload. */
    private static final char DATA_URL_SEPARATOR = ',';

    /** Media type reported to the provider when the bytes do not identify themselves. */
    private static final String DEFAULT_MEDIA_TYPE = "image/png";

    private static final String MEDIA_TYPE_JPEG = "image/jpeg";
    private static final String MEDIA_TYPE_GIF = "image/gif";
    private static final String MEDIA_TYPE_WEBP = "image/webp";
    private static final String MEDIA_TYPE_BMP = "image/bmp";

    /** Magic bytes of the formats the vision providers accept, used to label the data URL correctly. */
    private static final byte[] MAGIC_PNG = {(byte) 0x89, 'P', 'N', 'G'};
    private static final byte[] MAGIC_JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] MAGIC_GIF = {'G', 'I', 'F', '8'};
    private static final byte[] MAGIC_RIFF = {'R', 'I', 'F', 'F'};
    private static final byte[] MAGIC_WEBP = {'W', 'E', 'B', 'P'};
    private static final byte[] MAGIC_BMP = {'B', 'M'};

    /** Offset of the WEBP marker inside a RIFF container. */
    private static final int WEBP_MARKER_OFFSET = 8;

    private final VisionProvider visionProvider;
    private final KbProperties properties;

    /**
     * Validates the attached images, turns them into text and appends it to the query.
     *
     * @param query  written question, may be blank when images carry the whole question
     * @param images base64 encoded images, may be empty
     * @return query the retrieval pipeline runs with, plus the degradation markers it earned
     */
    public ImageQueryOutcome enrich(String query, List<String> images) {
        String text = query == null ? "" : query.trim();
        if (CollectionUtils.isEmpty(images)) {
            requireSomethingToSearch(text, false);
            return new ImageQueryOutcome(text, List.of());
        }
        List<byte[]> decoded = decodeAll(images);
        List<String> descriptions = describeAll(decoded);
        if (descriptions.size() != decoded.size()) {
            requireSomethingToSearch(text, true);
            log.info("image query degraded to the written question alone, images={}, understood={}",
                    decoded.size(), descriptions.size());
            return new ImageQueryOutcome(text,
                    List.of(DegradedReason.IMAGE_UNDERSTANDING_UNAVAILABLE.code()));
        }
        StringBuilder enriched = new StringBuilder(text);
        for (String description : descriptions) {
            if (enriched.length() > 0) {
                enriched.append(SEGMENT_SEPARATOR);
            }
            enriched.append(IMAGE_TEXT_PREFIX).append(description);
        }
        log.info("image query enriched, images={}, queryLength={}, enrichedLength={}",
                decoded.size(), text.length(), enriched.length());
        return new ImageQueryOutcome(enriched.toString(), List.of());
    }

    /**
     * Validates the attached images and decodes them into labelled inputs the multimodal route can embed,
     * the M14 contract section 7.
     *
     * <p>Reuses the exact count and size gate of {@link #enrich(String, List)}, so the image constraints
     * live in one place whether the pictures are transcribed into the query or embedded into the multimodal
     * space. The media type is derived from the bytes for the same reason the vision fallback derives it -
     * the wire format is bare base64 and a mislabelled data URL is rejected by some gateways.
     *
     * @param images base64 encoded images as the caller sent them, must not be empty
     * @return decoded inputs in the order they arrived
     */
    public List<ImageInput> decodeForEmbedding(List<String> images) {
        List<byte[]> decoded = decodeAll(images);
        List<ImageInput> inputs = new ArrayList<>(decoded.size());
        for (byte[] content : decoded) {
            inputs.add(new ImageInput(content, mediaTypeOf(content)));
        }
        return inputs;
    }

    /**
     * The single gate that rejects a call with nothing to search for.
     *
     * @param text     written question after trimming
     * @param degraded {@code true} when images were attached but could not be understood
     */
    private void requireSomethingToSearch(String text, boolean degraded) {
        if (!text.isEmpty()) {
            return;
        }
        if (degraded) {
            log.error("image only query cannot be served, errorCode={}", ErrorCode.INVALID_PARAM);
            throw BizException.invalidParam("图片理解不可用且未提供文字问题，无法检索：请补充文字问题后重试");
        }
        throw BizException.invalidParam("query 与 images 不能同时为空");
    }

    /**
     * Decodes and bounds every attached image.
     *
     * @param images base64 payloads as the caller sent them
     * @return decoded bytes in the order they arrived
     */
    private List<byte[]> decodeAll(List<String> images) {
        KbProperties.Retrieval config = properties.getRetrieval();
        if (images.size() > config.getImageQueryMaxCount()) {
            throw BizException.invalidParam("images 最多 " + config.getImageQueryMaxCount()
                    + " 张，当前 " + images.size() + " 张");
        }
        List<byte[]> decoded = new ArrayList<>(images.size());
        long total = 0L;
        for (String image : images) {
            byte[] content = decode(image);
            if (content.length > config.getImageQueryMaxBytes()) {
                throw BizException.invalidParam("单张图片解码后不得超过 "
                        + config.getImageQueryMaxBytes() + " 字节，当前 " + content.length + " 字节");
            }
            total += content.length;
            if (total > config.getImageQueryMaxTotalBytes()) {
                throw BizException.invalidParam("图片解码后总量不得超过 "
                        + config.getImageQueryMaxTotalBytes() + " 字节");
            }
            decoded.add(content);
        }
        return decoded;
    }

    /**
     * Decodes one payload, tolerating a data URL header.
     *
     * @param image base64 payload, optionally prefixed with a data URL header
     * @return decoded bytes
     */
    private byte[] decode(String image) {
        if (image == null || image.isBlank()) {
            throw BizException.invalidParam("images 不能包含空元素");
        }
        String payload = image.trim();
        if (payload.startsWith(DATA_URL_PREFIX)) {
            int separator = payload.indexOf(DATA_URL_SEPARATOR);
            if (separator < 0) {
                throw BizException.invalidParam("images 元素不是合法的 base64 图片");
            }
            payload = payload.substring(separator + 1);
        }
        try {
            byte[] content = Base64.getDecoder().decode(payload);
            if (content.length == 0) {
                throw BizException.invalidParam("images 不能包含空图片");
            }
            return content;
        } catch (IllegalArgumentException e) {
            throw BizException.invalidParam("images 元素不是合法的 base64 图片");
        }
    }

    /**
     * Runs the vision model once per image, stopping at the first failure.
     *
     * <p>Stopping is deliberate: the degradation is all or nothing, so once one image is lost the
     * descriptions of the others are thrown away anyway and paying for them would be waste.
     *
     * @param images decoded images in order
     * @return descriptions, shorter than the input when the understanding failed
     */
    private List<String> describeAll(List<byte[]> images) {
        if (!visionProvider.isConfigured()) {
            log.info("query images ignored because no vision model is configured, images={}", images.size());
            return List.of();
        }
        List<String> descriptions = new ArrayList<>(images.size());
        for (byte[] image : images) {
            try {
                String description = visionProvider.describeImage(image, mediaTypeOf(image));
                if (description == null || description.isBlank()) {
                    log.info("vision provider returned no text for a query image, bytes={}", image.length);
                    return descriptions;
                }
                descriptions.add(description.trim());
            } catch (Exception e) {
                log.error("query image understanding failed, errorCode={}, bytes={}",
                        ErrorCode.UPSTREAM_MODEL_ERROR, image.length, e);
                return descriptions;
            }
        }
        return descriptions;
    }

    /**
     * Labels the bytes so the provider's data URL declares the format it really carries.
     *
     * <p>Derived from the content rather than from the caller: the wire format is bare base64 with no
     * media type, and mislabelling a JPEG as a PNG is rejected by some gateways.
     *
     * @param content decoded image bytes
     * @return MIME type, the PNG default when the bytes identify no known format
     */
    private String mediaTypeOf(byte[] content) {
        if (startsWith(content, MAGIC_PNG, 0)) {
            return DEFAULT_MEDIA_TYPE;
        }
        if (startsWith(content, MAGIC_JPEG, 0)) {
            return MEDIA_TYPE_JPEG;
        }
        if (startsWith(content, MAGIC_GIF, 0)) {
            return MEDIA_TYPE_GIF;
        }
        if (startsWith(content, MAGIC_RIFF, 0) && startsWith(content, MAGIC_WEBP, WEBP_MARKER_OFFSET)) {
            return MEDIA_TYPE_WEBP;
        }
        if (startsWith(content, MAGIC_BMP, 0)) {
            return MEDIA_TYPE_BMP;
        }
        return DEFAULT_MEDIA_TYPE;
    }

    private boolean startsWith(byte[] content, byte[] magic, int offset) {
        if (content.length < offset + magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (content[offset + i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * What the image stage produced.
     *
     * @param query    query the retrieval pipeline and the audit digest both use
     * @param degraded degradation markers earned by the image stage, empty on success
     */
    public record ImageQueryOutcome(String query, List<String> degraded) {
    }
}
