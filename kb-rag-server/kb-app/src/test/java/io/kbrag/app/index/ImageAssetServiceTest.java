package io.kbrag.app.index;

import io.kbrag.common.exception.ProviderErrorType;
import io.kbrag.common.exception.ProviderException;
import io.kbrag.domain.config.KbProperties;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the promise that an image never fails a document.
 *
 * <p>Three failure modes are exercised — no credential, an empty answer and a provider exception — and in all
 * three the asset is still stored, the status records what happened, and nothing is thrown at the pipeline.
 * A scanned page is the same case one level up: the page has no text layer, so a skipped vision call means the
 * rest of the document has to carry on without it.
 *
 * <p>The parallel describe stage is covered by what it must not break rather than by a stopwatch: rows land
 * in reading order however the calls interleave, and a storage failure still reaches the pipeline unwrapped.
 *
 * @author owlzhangfq@gmail.com
 */
class ImageAssetServiceTest {

    private static final String KB_ID = "kb_test";
    private static final String DOC_ID = "doc_test";
    private static final String VERSION_ID = "dv_test";

    private ImageAssetMapper imageAssetMapper;
    private ObjectStorage objectStorage;
    private VisionProvider visionProvider;
    private KbProperties properties;
    private ImageAssetService service;

    @BeforeEach
    void setUp() {
        imageAssetMapper = mock(ImageAssetMapper.class);
        objectStorage = mock(ObjectStorage.class);
        visionProvider = mock(VisionProvider.class);
        properties = new KbProperties();
        BizIdGenerator bizIdGenerator = new BizIdGenerator();
        service = new ImageAssetService(imageAssetMapper, objectStorage, visionProvider,
                bizIdGenerator, properties);
        when(imageAssetMapper.selectList(any())).thenReturn(List.of());
    }

    @Test
    void shouldStoreAndDescribeEveryImage() {
        when(visionProvider.isConfigured()).thenReturn(true);
        when(visionProvider.describeImage(any(), anyString())).thenReturn("a pipeline diagram");

        List<ImageAsset> assets = service.materialize(document("pdf"), version(),
                parsed(image("img_1", 1, "EMBEDDED"), image("img_2", 2, "PAGE_RENDER")),
                new ArrayList<>());

        assertEquals(2, assets.size());
        assertEquals(ImageAssetStatus.DONE, assets.get(0).getStatus());
        assertEquals("a pipeline diagram", assets.get(0).getTextProxy());
        assertEquals(ImageAssetKind.PAGE_RENDER, assets.get(1).getKind());
        verify(objectStorage, times(2)).put(anyString(), any(), anyLong(), anyString());
        verify(imageAssetMapper, times(2)).insert(any(ImageAsset.class));
    }

    @Test
    void shouldBuildAnObjectKeyScopedToTheVersion() {
        when(visionProvider.isConfigured()).thenReturn(false);

        service.materialize(document("pdf"), version(), parsed(image("img_1", 1, "EMBEDDED")),
                new ArrayList<>());

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(objectStorage).put(key.capture(), any(), anyLong(), anyString());
        assertEquals("kb/kb_test/doc/doc_test/dv_test/images/img_1.png", key.getValue());
    }

    @Test
    void shouldSkipTheProxyWithoutAVisionCredential() {
        when(visionProvider.isConfigured()).thenReturn(false);

        List<ImageAsset> assets = service.materialize(document("pdf"), version(),
                parsed(image("img_1", 1, "PAGE_RENDER")), new ArrayList<>());

        // Zero key mode: the image is stored so a later backfill can describe it, and the document is
        // indexed without its text.
        assertEquals(1, assets.size());
        assertEquals(ImageAssetStatus.SKIPPED, assets.get(0).getStatus());
        assertFalse(assets.get(0).hasTextProxy());
        assertNotNull(assets.get(0).getObjectKey());
        verify(visionProvider, never()).describeImage(any(), anyString());
    }

    @Test
    void shouldRecordAFailedProxyWithoutPropagating() {
        when(visionProvider.isConfigured()).thenReturn(true);
        when(visionProvider.describeImage(any(), anyString())).thenThrow(
                new ProviderException("dashscope", ProviderErrorType.QUOTA_EXCEEDED, "quota exhausted"));

        List<ImageAsset> assets = service.materialize(document("pdf"), version(),
                parsed(image("img_1", 1, "EMBEDDED")), new ArrayList<>());

        // Rejecting a fifty page report over one unreadable diagram is never what an operator wants.
        assertEquals(ImageAssetStatus.FAILED, assets.get(0).getStatus());
        assertNotNull(assets.get(0).getFailReason());
        assertFalse(assets.get(0).hasTextProxy());
    }

    @Test
    void shouldTreatABlankProxyAsSkipped() {
        when(visionProvider.isConfigured()).thenReturn(true);
        when(visionProvider.describeImage(any(), anyString())).thenReturn("   ");

        List<ImageAsset> assets = service.materialize(document("pdf"), version(),
                parsed(image("img_1", 1, "EMBEDDED")), new ArrayList<>());

        // A blank answer is not a usable proxy, and storing it would look like a successful call.
        assertEquals(ImageAssetStatus.SKIPPED, assets.get(0).getStatus());
    }

    @Test
    void shouldStopAtTheImageLimitAndWarn() {
        properties.getImage().setMaxPerDocument(2);
        when(visionProvider.isConfigured()).thenReturn(false);
        List<String> warnings = new ArrayList<>();

        List<ImageAsset> assets = service.materialize(document("pdf"), version(),
                parsed(image("img_1", 1, "EMBEDDED"), image("img_2", 1, "EMBEDDED"),
                        image("img_3", 1, "EMBEDDED")), warnings);

        assertEquals(2, assets.size());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("image limit"));
    }

    @Test
    void shouldSkipAnOversizedImageAndWarn() {
        properties.getImage().setMaxImageSizeMb(0);
        when(visionProvider.isConfigured()).thenReturn(false);
        List<String> warnings = new ArrayList<>();

        List<ImageAsset> assets = service.materialize(document("pdf"), version(),
                parsed(image("img_1", 1, "EMBEDDED")), warnings);

        assertTrue(assets.isEmpty());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("size out of bounds"));
    }

    @Test
    void shouldReuseTheAssetsOfAVersionInsteadOfCallingTheModelAgain() {
        ImageAsset existing = new ImageAsset();
        existing.setImageId("img_existing");
        existing.setDocumentVersionId(VERSION_ID);
        when(imageAssetMapper.selectList(any())).thenReturn(List.of(existing));

        List<ImageAsset> assets = service.materialize(document("pdf"), version(),
                parsed(image("img_1", 1, "EMBEDDED")), new ArrayList<>());

        // A rebuild only changes how the text is cut, so paying for the vision calls again would be waste.
        assertEquals(List.of("img_existing"), assets.stream().map(ImageAsset::getImageId).toList());
        verify(visionProvider, never()).describeImage(any(), anyString());
        verify(objectStorage, never()).put(anyString(), any(), anyLong(), anyString());
    }

    @Test
    void shouldReturnNothingForADocumentWithoutImages() {
        assertTrue(service.materialize(document("pdf"), version(), parsed(), new ArrayList<>()).isEmpty());
        verify(objectStorage, never()).put(anyString(), any(), anyLong(), anyString());
    }

    @Test
    void shouldMaterializeAStandaloneUploadAsAnImageAsset() {
        when(visionProvider.isConfigured()).thenReturn(true);
        when(visionProvider.describeImage(any(), anyString())).thenReturn("a screenshot of a dashboard");

        ImageAsset asset = service.materializeStandalone(document("png"), version(),
                new byte[]{1, 2, 3});

        assertEquals(ImageAssetKind.STANDALONE, asset.getKind());
        assertEquals("image/png", asset.getMediaType());
        assertEquals("a screenshot of a dashboard", asset.getTextProxy());
        assertEquals(service.standaloneSourceId(document("png")), asset.getSourceImageId());
    }

    @Test
    void shouldRecogniseTheStandaloneImageExtensions() {
        assertTrue(service.isStandaloneImage("png"));
        assertTrue(service.isStandaloneImage("JPG"));
        assertFalse(service.isStandaloneImage("pdf"));
        assertFalse(service.isStandaloneImage(null));
    }

    @Test
    void shouldNotDescribeAPageTheParserOcrEngineAlreadyRead() {
        when(visionProvider.isConfigured()).thenReturn(true);

        service.materialize(document("pdf"), version(),
                ocrParsed(1, image("img_1", 1, "PAGE_RENDER")), new ArrayList<>());

        // The OCR text is already in the parse artifact, so the vision call would only transcribe the same
        // page a second time and be billed for it.
        verify(visionProvider, never()).describeImage(any(), anyString());
        assertEquals(ImageAssetStatus.SKIPPED, captureAsset().getStatus());
    }

    @Test
    void shouldStillDescribeAnIllustrationOnAnOcrReadPage() {
        when(visionProvider.isConfigured()).thenReturn(true);
        when(visionProvider.describeImage(any(), anyString())).thenReturn("a chart");

        service.materialize(document("pdf"), version(),
                ocrParsed(1, image("img_1", 1, "EMBEDDED")), new ArrayList<>());

        // An embedded picture is not the page: its description carries information the OCR text does not.
        verify(visionProvider).describeImage(any(), anyString());
        assertEquals("a chart", captureAsset().getTextProxy());
    }

    @Test
    void shouldDescribeAPageRenderOfAPageNoOcrEngineRead() {
        when(visionProvider.isConfigured()).thenReturn(true);
        when(visionProvider.describeImage(any(), anyString())).thenReturn("page text");

        service.materialize(document("pdf"), version(),
                ocrParsed(2, image("img_1", 1, "PAGE_RENDER")), new ArrayList<>());

        verify(visionProvider).describeImage(any(), anyString());
    }

    @Test
    void shouldIssueTheVisionCallsOfOneDocumentAtTheSameTime() throws Exception {
        int images = 4;
        properties.getImage().setDescribeConcurrency(images);
        // Every call blocks until all of them have arrived, so this only completes if they overlap. Serial
        // calls would time out on the latch instead, which is the regression this test exists to catch.
        CountDownLatch arrived = new CountDownLatch(images);
        when(visionProvider.isConfigured()).thenReturn(true);
        when(visionProvider.describeImage(any(), anyString())).thenAnswer(invocation -> {
            arrived.countDown();
            assertTrue(arrived.await(5, TimeUnit.SECONDS), "vision calls were issued one after another");
            return "a diagram";
        });

        List<ImageAsset> assets = service.materialize(document("pdf"), version(),
                parsed(image("img_1", 1, "EMBEDDED"), image("img_2", 2, "EMBEDDED"),
                        image("img_3", 3, "EMBEDDED"), image("img_4", 4, "EMBEDDED")),
                new ArrayList<>());

        assertEquals(images, assets.size());
        assertTrue(assets.stream().allMatch(asset -> asset.getStatus() == ImageAssetStatus.DONE));
    }

    @Test
    void shouldInsertTheRowsInReadingOrderWhateverOrderTheCallsFinishIn() {
        properties.getImage().setDescribeConcurrency(4);
        when(visionProvider.isConfigured()).thenReturn(true);
        // The last image answers first and the first one last: the row order must come from the document,
        // not from the provider, because findByVersion orders by the primary key and the placeholder
        // resolver reads that back as the order the images appear in the markdown.
        when(visionProvider.describeImage(any(), anyString())).thenAnswer(invocation -> {
            byte[] content = invocation.getArgument(0);
            Thread.sleep(60L / content[0]);
            return "image " + content[0];
        });
        List<String> inserted = new CopyOnWriteArrayList<>();
        when(imageAssetMapper.insert(any(ImageAsset.class))).thenAnswer(invocation -> {
            inserted.add(invocation.<ImageAsset>getArgument(0).getSourceImageId());
            return 1;
        });

        List<ImageAsset> assets = service.materialize(document("pdf"), version(),
                parsed(ordered("img_1", 1), ordered("img_2", 2), ordered("img_3", 3)),
                new ArrayList<>());

        assertEquals(List.of("img_1", "img_2", "img_3"), inserted);
        assertEquals(List.of("img_1", "img_2", "img_3"),
                assets.stream().map(ImageAsset::getSourceImageId).toList());
    }

    @Test
    void shouldReportAStorageFailureUnwrapped() {
        properties.getImage().setDescribeConcurrency(4);
        doThrow(new IllegalStateException("bucket unreachable"))
                .when(objectStorage).put(anyString(), any(), anyLong(), anyString());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.materialize(document("pdf"), version(),
                        parsed(image("img_1", 1, "EMBEDDED"), image("img_2", 2, "EMBEDDED")),
                        new ArrayList<>()));

        // Not a CompletionException: the pipeline records the reason it recorded before the calls became
        // parallel, and nothing is inserted for a version whose images never reached storage.
        assertEquals("bucket unreachable", failure.getMessage());
        verify(imageAssetMapper, never()).insert(any(ImageAsset.class));
    }

    @Test
    void shouldFallBackToSerialCallsWhenConcurrencyIsMisconfigured() {
        properties.getImage().setDescribeConcurrency(0);
        when(visionProvider.isConfigured()).thenReturn(true);
        when(visionProvider.describeImage(any(), anyString())).thenReturn("a diagram");

        List<ImageAsset> assets = service.materialize(document("pdf"), version(),
                parsed(image("img_1", 1, "EMBEDDED"), image("img_2", 2, "EMBEDDED")),
                new ArrayList<>());

        // A zero or negative setting is clamped rather than refused: it must degrade to the pre-change
        // behaviour, never to a document that stores no image at all.
        assertEquals(2, assets.size());
        verify(visionProvider, times(2)).describeImage(any(), anyString());
    }

    private ImageAsset captureAsset() {
        ArgumentCaptor<ImageAsset> captor = ArgumentCaptor.forClass(ImageAsset.class);
        verify(imageAssetMapper).insert(captor.capture());
        return captor.getValue();
    }

    /**
     * A parse result whose page was read by the parser's own OCR engine.
     *
     * @param ocrPageNo page the engine read
     * @param images    images of the document
     * @return parse result
     */
    private ParsedDocument ocrParsed(int ocrPageNo, ParsedDocument.ParsedImage... images) {
        return ParsedDocument.builder()
                .markdown("body")
                .images(List.of(images))
                .pages(List.of(ParsedDocument.ParsedPage.builder()
                        .pageNo(ocrPageNo)
                        .text("recognised text")
                        .scanned(true)
                        .ocrSource("paddle")
                        .build()))
                .build();
    }

    private ParsedDocument parsed(ParsedDocument.ParsedImage... images) {
        return ParsedDocument.builder().markdown("body").images(List.of(images)).build();
    }

    private ParsedDocument.ParsedImage image(String imageId, Integer pageNo, String kind) {
        return ParsedDocument.ParsedImage.builder()
                .imageId(imageId)
                .pageNo(pageNo)
                .kind(kind)
                .mediaType("image/png")
                .content(new byte[]{1, 2, 3, 4})
                .build();
    }

    /**
     * An image whose first content byte identifies it, so a stubbed vision call can answer per image and
     * stagger its own latency.
     *
     * @param imageId source image identifier
     * @param order   position in reading order, starting at one
     * @return parser supplied image
     */
    private ParsedDocument.ParsedImage ordered(String imageId, int order) {
        return ParsedDocument.ParsedImage.builder()
                .imageId(imageId)
                .pageNo(order)
                .kind("EMBEDDED")
                .mediaType("image/png")
                .content(new byte[]{(byte) order})
                .build();
    }

    private Document document(String fileExt) {
        Document document = new Document();
        document.setDocId(DOC_ID);
        document.setKbId(KB_ID);
        document.setFileName("report." + fileExt);
        document.setFileExt(fileExt);
        return document;
    }

    private DocumentVersion version() {
        DocumentVersion version = new DocumentVersion();
        version.setVersionId(VERSION_ID);
        version.setDocId(DOC_ID);
        return version;
    }
}
