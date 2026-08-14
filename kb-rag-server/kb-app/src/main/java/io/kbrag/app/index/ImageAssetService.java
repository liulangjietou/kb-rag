package io.kbrag.app.index;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.context.ModelUsageContextHolder;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.entity.DocumentVersion;
import io.kbrag.domain.entity.ImageAsset;
import io.kbrag.domain.enums.ImageAssetKind;
import io.kbrag.domain.enums.ImageAssetStatus;
import io.kbrag.domain.mapper.ImageAssetMapper;
import io.kbrag.domain.model.ParsedDocument;
import io.kbrag.domain.port.ObjectStorage;
import io.kbrag.domain.port.VisionProvider;
import io.kbrag.domain.service.BizIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Stores the images of a document version and turns each one into a textual proxy.
 *
 * <p><b>An image never fails a document.</b> Storage succeeds or the asset is skipped; the vision call
 * succeeds, is skipped when no credential exists, or is recorded as failed. In all three cases the rest
 * of the document is indexed, because the alternative — rejecting a fifty page report over one
 * unreadable diagram — is never the behaviour an operator wants. The status column keeps the evidence so
 * a later pass can fill in what was missed.
 *
 * <p><b>Assets are materialised once per version.</b> A rerun or a rebuild finds the existing rows and
 * reuses their proxies instead of paying for the vision calls again. That is what makes tuning the split
 * or the cleaning rules cheap: those stages do not depend on the model, so they must not re-invoke it.
 *
 * <p><b>The vision calls of one document run in parallel.</b> Each image costs one round trip bounded by
 * {@code kb.vision.timeout-ms}, so a document at the image ceiling took half an hour of wall clock when
 * they were issued one after another — the indexing of a single illustrated report would occupy a
 * pipeline slot for that whole time. The rows are still inserted sequentially in reading order
 * afterwards: {@link #findByVersion} orders by the primary key, and the placeholder resolver depends on
 * that order matching the order the images appear in the markdown.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageAssetService {

    private static final String OBJECT_KEY_TEMPLATE = "kb/%s/doc/%s/%s/images/%s.%s";
    private static final String DEFAULT_MEDIA_TYPE = "image/png";
    private static final String DEFAULT_EXTENSION = "bin";
    private static final int BYTES_PER_MB = 1024 * 1024;
    private static final int FAIL_REASON_MAX_LENGTH = 1024;
    private static final String THREAD_PREFIX = "kb-image-";
    private static final int MIN_CONCURRENCY = 1;

    /** Extension per MIME type, so an object key stays recognisable in a storage browser. */
    private static final Map<String, String> EXTENSION_BY_MEDIA_TYPE = Map.of(
            "image/png", "png",
            "image/jpeg", "jpg",
            "image/jpg", "jpg",
            "image/webp", "webp",
            "image/gif", "gif",
            "image/bmp", "bmp",
            "image/tiff", "tiff");

    private final ImageAssetMapper imageAssetMapper;
    private final ObjectStorage objectStorage;
    private final VisionProvider visionProvider;
    private final BizIdGenerator bizIdGenerator;
    private final KbProperties properties;

    /**
     * Stores and describes every image of a parse result.
     *
     * @param document document record
     * @param version  version being built
     * @param parsed   parse result carrying the image binaries
     * @param warnings collector the caller surfaces in the preview, appended to
     * @return asset rows in reading order, empty when the document holds no image
     */
    public List<ImageAsset> materialize(Document document, DocumentVersion version,
                                        ParsedDocument parsed, List<String> warnings) {
        List<ImageAsset> existing = findByVersion(version.getVersionId());
        if (CollectionUtils.isNotEmpty(existing)) {
            log.info("image assets reused, docId={}, versionId={}, images={}",
                    document.getDocId(), version.getVersionId(), existing.size());
            return existing;
        }
        List<ParsedDocument.ParsedImage> images = parsed.imagesOrEmpty();
        if (CollectionUtils.isEmpty(images)) {
            return List.of();
        }
        // Pages the parser already read with its own OCR engine: their rendered image is stored like any
        // other, but describing it would ask a vision model to transcribe text the artifact already holds.
        Set<Integer> ocrPages = parsed.ocrPageNumbers();
        int limit = properties.getImage().getMaxPerDocument();
        long maxBytes = (long) properties.getImage().getMaxImageSizeMb() * BYTES_PER_MB;
        List<ParsedDocument.ParsedImage> accepted = new ArrayList<>(Math.min(images.size(), limit));
        for (ParsedDocument.ParsedImage image : images) {
            if (accepted.size() >= limit) {
                warnings.add("image limit of " + limit + " reached, " + (images.size() - accepted.size())
                        + " image(s) skipped");
                log.info("image limit reached, docId={}, limit={}, total={}",
                        document.getDocId(), limit, images.size());
                break;
            }
            if (image.getContent() == null || image.getContent().length == 0
                    || image.getContent().length > maxBytes) {
                warnings.add("image " + image.getImageId() + " skipped, size out of bounds");
                continue;
            }
            accepted.add(image);
        }
        List<ImageAsset> assets = storeAndDescribeAll(document, version, accepted, ocrPages);
        for (ImageAsset asset : assets) {
            imageAssetMapper.insert(asset);
        }
        log.info("image assets materialized, docId={}, versionId={}, images={}, visionConfigured={}",
                document.getDocId(), version.getVersionId(), assets.size(), visionProvider.isConfigured());
        return assets;
    }

    /**
     * Stores and describes an uploaded file that is itself an image.
     *
     * @param document document record
     * @param version  version being built
     * @param content  raw image bytes
     * @return asset row
     */
    public ImageAsset materializeStandalone(Document document, DocumentVersion version, byte[] content) {
        List<ImageAsset> existing = findByVersion(version.getVersionId());
        if (CollectionUtils.isNotEmpty(existing)) {
            return existing.get(0);
        }
        ParsedDocument.ParsedImage image = ParsedDocument.ParsedImage.builder()
                .imageId(standaloneSourceId(document))
                .kind(ImageAssetKind.STANDALONE.name())
                .mediaType(mediaTypeOf(document.getFileExt()))
                .content(content)
                .build();
        return persist(document, version, image, Set.of());
    }

    /**
     * Loads the assets of a version in reading order.
     *
     * @param versionId document version business id
     * @return asset rows
     */
    public List<ImageAsset> findByVersion(String versionId) {
        return imageAssetMapper.selectList(new LambdaQueryWrapper<ImageAsset>()
                .eq(ImageAsset::getDocumentVersionId, versionId)
                .orderByAsc(ImageAsset::getId));
    }

    /**
     * Deletes the assets of a version, used when a re-parse replaces them.
     *
     * @param versionId document version business id
     */
    public void deleteByVersion(String versionId) {
        int removed = imageAssetMapper.delete(new LambdaQueryWrapper<ImageAsset>()
                .eq(ImageAsset::getDocumentVersionId, versionId));
        if (removed > 0) {
            log.info("image assets discarded, versionId={}, count={}", versionId, removed);
        }
    }

    /**
     * Stores one image and produces its textual proxy.
     *
     * @param document document record
     * @param version  version being built
     * @param image    parser supplied image
     * @param ocrPages page numbers an OCR engine already read
     * @return persisted asset row
     */
    private ImageAsset persist(Document document, DocumentVersion version,
                               ParsedDocument.ParsedImage image, Set<Integer> ocrPages) {
        ImageAsset asset = newAsset(document, version, image);
        storeAndDescribe(asset, image, ocrPages);
        imageAssetMapper.insert(asset);
        return asset;
    }

    /**
     * Stores and describes a whole document's images, several at a time.
     *
     * <p>The returned rows follow the reading order of the argument whatever order the calls complete in,
     * because that order is what the caller inserts them in and therefore what the placeholder resolver
     * later reads back.
     *
     * <p>A storage failure still ends the document as it did before. It is unwrapped from the
     * {@link CompletionException} the join would otherwise raise, so the pipeline records the same reason
     * it always recorded. A failing vision call is not a failure here at all — {@link #describe} turns it
     * into a status on the row.
     *
     * @param document document record
     * @param version  version being built
     * @param images   images that passed the count and size limits, in reading order
     * @param ocrPages page numbers an OCR engine already read
     * @return asset rows in reading order, not yet inserted
     */
    private List<ImageAsset> storeAndDescribeAll(Document document, DocumentVersion version,
                                                 List<ParsedDocument.ParsedImage> images,
                                                 Set<Integer> ocrPages) {
        List<ImageAsset> assets = new ArrayList<>(images.size());
        for (ParsedDocument.ParsedImage image : images) {
            assets.add(newAsset(document, version, image));
        }
        int concurrency = Math.min(
                Math.max(MIN_CONCURRENCY, properties.getImage().getDescribeConcurrency()), images.size());
        if (concurrency <= 1) {
            for (int index = 0; index < images.size(); index++) {
                storeAndDescribe(assets.get(index), images.get(index), ocrPages);
            }
            return assets;
        }
        ExecutorService executor = Executors.newFixedThreadPool(concurrency,
                runnable -> new Thread(runnable, THREAD_PREFIX + document.getDocId()));
        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>(images.size());
            for (int index = 0; index < images.size(); index++) {
                ImageAsset asset = assets.get(index);
                ParsedDocument.ParsedImage image = images.get(index);
                futures.add(CompletableFuture.runAsync(
                        ModelUsageContextHolder.wrap(() -> storeAndDescribe(asset, image, ocrPages)), executor));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (CompletionException e) {
            throw e.getCause() instanceof RuntimeException cause ? cause : e;
        } finally {
            executor.shutdown();
        }
        log.info("image assets described in parallel, docId={}, images={}, concurrency={}",
                document.getDocId(), images.size(), concurrency);
        return assets;
    }

    /**
     * Everything about an asset row that needs no network call.
     *
     * <p>Built on the calling thread, so the business id sequence and the reading order stay outside the
     * concurrent part of the work.
     *
     * @param document document record
     * @param version  version being built
     * @param image    parser supplied image
     * @return asset row without its status or proxy
     */
    private ImageAsset newAsset(Document document, DocumentVersion version,
                                ParsedDocument.ParsedImage image) {
        String mediaType = image.getMediaType() == null || image.getMediaType().isBlank()
                ? DEFAULT_MEDIA_TYPE : image.getMediaType();
        ImageAsset asset = new ImageAsset();
        asset.setImageId(bizIdGenerator.imageAssetId());
        asset.setSourceImageId(image.getImageId());
        asset.setKbId(document.getKbId());
        asset.setDocId(document.getDocId());
        asset.setDocumentVersionId(version.getVersionId());
        asset.setPageNo(image.getPageNo());
        asset.setKind(ImageAssetKind.from(image.getKind()));
        asset.setObjectKey(String.format(OBJECT_KEY_TEMPLATE, document.getKbId(), document.getDocId(),
                version.getVersionId(), image.getImageId(), extensionOf(mediaType)));
        asset.setMediaType(mediaType);
        asset.setBytes((long) image.getContent().length);
        return asset;
    }

    /**
     * The two network calls of one image: upload the bytes, then describe them.
     *
     * <p>Touches nothing but the given row, which is what lets the caller run this over many images at
     * once and read every result back after the join.
     *
     * @param asset    asset row being filled in
     * @param image    parser supplied image
     * @param ocrPages page numbers an OCR engine already read
     */
    private void storeAndDescribe(ImageAsset asset, ParsedDocument.ParsedImage image,
                                  Set<Integer> ocrPages) {
        objectStorage.put(asset.getObjectKey(), new ByteArrayInputStream(image.getContent()),
                image.getContent().length, asset.getMediaType());
        if (alreadyRead(asset, ocrPages)) {
            asset.setStatus(ImageAssetStatus.SKIPPED);
            log.info("page already read by the parser OCR engine, vision call skipped, objectKey={}",
                    asset.getObjectKey());
            return;
        }
        describe(asset, image.getContent(), asset.getMediaType());
    }

    /**
     * Tells whether the text of a rendered page was already produced without a vision model.
     *
     * <p>Only a page render qualifies. An embedded illustration on an OCR read page is still a picture
     * whose description carries information the OCR text does not, so it goes through the vision model as
     * it always did.
     *
     * @param asset    asset being built
     * @param ocrPages page numbers an OCR engine already read
     * @return {@code true} when the vision call would only repeat what the parser already returned
     */
    private boolean alreadyRead(ImageAsset asset, Set<Integer> ocrPages) {
        return asset.getKind() == ImageAssetKind.PAGE_RENDER && asset.getPageNo() != null
                && ocrPages.contains(asset.getPageNo());
    }

    /**
     * Fills in the textual proxy, classifying the outcome instead of propagating a failure.
     *
     * @param asset     asset being built
     * @param content   raw image bytes
     * @param mediaType MIME type of the bytes
     */
    private void describe(ImageAsset asset, byte[] content, String mediaType) {
        if (!visionProvider.isConfigured()) {
            asset.setStatus(ImageAssetStatus.SKIPPED);
            log.info("vision provider unconfigured, image stored without a text proxy, objectKey={}",
                    asset.getObjectKey());
            return;
        }
        try {
            String proxy = visionProvider.describeImage(content, mediaType);
            if (proxy == null || proxy.isBlank()) {
                asset.setStatus(ImageAssetStatus.SKIPPED);
                log.info("vision provider returned no text, objectKey={}", asset.getObjectKey());
                return;
            }
            asset.setTextProxy(proxy.trim());
            asset.setStatus(ImageAssetStatus.DONE);
        } catch (Exception e) {
            asset.setStatus(ImageAssetStatus.FAILED);
            asset.setFailReason(truncate(e.getMessage()));
            log.error("vision call failed, errorCode={}, objectKey={}",
                    io.kbrag.common.api.ErrorCode.UPSTREAM_MODEL_ERROR, asset.getObjectKey(), e);
        }
    }

    /**
     * Source identifier of a standalone upload.
     *
     * <p>Derived from the document id rather than random, so the placeholder the pipeline synthesises for
     * it is reproducible across reruns.
     *
     * @param document document record
     * @return placeholder identifier
     */
    public String standaloneSourceId(Document document) {
        return "img_" + document.getDocId().replace('-', '_');
    }

    /**
     * Tells whether an uploaded extension is an image rather than a document.
     *
     * @param fileExt lower case extension without the dot
     * @return {@code true} when the upload is a standalone image
     */
    public boolean isStandaloneImage(String fileExt) {
        return fileExt != null && properties.getImage().getStandaloneExtensions()
                .contains(fileExt.toLowerCase(Locale.ROOT));
    }

    private String mediaTypeOf(String fileExt) {
        String extension = fileExt == null ? "" : fileExt.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : EXTENSION_BY_MEDIA_TYPE.entrySet()) {
            if (entry.getValue().equals(extension)) {
                return entry.getKey();
            }
        }
        return DEFAULT_MEDIA_TYPE;
    }

    private String extensionOf(String mediaType) {
        return EXTENSION_BY_MEDIA_TYPE.getOrDefault(mediaType.toLowerCase(Locale.ROOT), DEFAULT_EXTENSION);
    }

    private String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() > FAIL_REASON_MAX_LENGTH ? reason.substring(0, FAIL_REASON_MAX_LENGTH) : reason;
    }
}
