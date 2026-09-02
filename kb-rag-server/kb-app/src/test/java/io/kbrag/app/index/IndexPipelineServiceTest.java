package io.kbrag.app.index;

import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.app.support.MybatisLambdaCache;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.entity.DocumentVersion;
import io.kbrag.domain.entity.KbTask;
import io.kbrag.domain.enums.ProcessStatus;
import io.kbrag.domain.enums.DocumentVersionStatus;
import io.kbrag.domain.enums.TaskType;
import io.kbrag.domain.mapper.ChunkMapper;
import io.kbrag.domain.mapper.DocumentMapper;
import io.kbrag.domain.mapper.DocumentVersionMapper;
import io.kbrag.domain.model.KbIndexConfig;
import io.kbrag.domain.model.ParsePreview;
import io.kbrag.domain.model.ParsedDocument;
import io.kbrag.domain.model.ProxiedContent;
import io.kbrag.domain.port.DocumentParserClient;
import io.kbrag.domain.port.EmbeddingProvider;
import io.kbrag.domain.port.ObjectStorage;
import io.kbrag.domain.port.VisionProvider;
import io.kbrag.domain.service.DocumentCleaner;
import io.kbrag.domain.service.DocumentVersionPlanner;
import io.kbrag.domain.service.ImagePlaceholderResolver;
import io.kbrag.domain.service.PageSplitter;
import io.kbrag.domain.service.PagedContentAssembler;
import io.kbrag.domain.service.VersionFingerprintFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the routing decisions of the index pipeline, not the extraction it delegates.
 *
 * <p>Every stage of a build lives behind a collaborator - the parser client, {@link ChunkSplitter},
 * {@link ChunkIndexWriter}, {@link VersionActivationHandler}, {@link IndexTaskLifecycle} - and each of
 * those is tested where it lives. What is left here, and what these tests are about, is the part no
 * collaborator can check: <em>which</em> of them run, in which order, and what the document is left
 * looking like when one of them throws.
 *
 * <p>Three risks make that worth its own test class. A reuse that silently produced nothing would leave
 * a document indexed with zero chunks - invisible until somebody searches for it. A parse failure
 * reported as an index failure sends an operator to the wrong half of the system. And a knowledge base
 * that requires a parse confirmation must never reach the engines before a human says so, which is a
 * property of what is <em>not</em> called.
 *
 * @author owlzhangfq@gmail.com
 */
class IndexPipelineServiceTest {

    private static final String KB_ID = "kb_test";
    private static final String DOC_ID = "doc_test";
    private static final String VERSION_ID = "dv_test";
    private static final String SOURCE_VERSION_ID = "dv_source";
    private static final String PARSED_OBJECT = "kb/kb_test/doc/doc_test/dv_source/parsed.json";
    private static final String MARKDOWN = "hello world";

    private DocumentMapper documentMapper;
    private DocumentVersionMapper documentVersionMapper;
    private ChunkMapper chunkMapper;
    private ObjectStorage objectStorage;
    private DocumentParserClient parserClient;
    private EmbeddingProvider embeddingProvider;
    private ChunkEmbedder chunkEmbedder;
    private ChunkSplitter chunkSplitter;
    private IndexTaskLifecycle taskLifecycle;
    private VisionProvider visionProvider;
    private ChunkIndexWriter chunkIndexWriter;
    private MultimodalIndexManager multimodalIndexManager;
    private KnowledgeBaseService knowledgeBaseService;
    private VersionFingerprintFactory fingerprintFactory;
    private ImageAssetService imageAssetService;
    private DocumentCleaner documentCleaner;
    private PagedContentAssembler pagedContentAssembler;
    private ImagePlaceholderResolver placeholderResolver;
    private VersionArtifactReuser versionArtifactReuser;
    private VersionActivationHandler activationHandler;

    private Document document;
    private DocumentVersion version;
    private KbIndexConfig config;
    private KbTask task;
    private IndexPipelineService service;

    @BeforeEach
    void setUp() {
        // The pipeline writes null into fail_reason, which updateById skips by design, so it builds
        // lambda update wrappers - and those need the column cache a Spring scan would have filled.
        MybatisLambdaCache.register(Document.class, DocumentVersion.class, Chunk.class);
        documentMapper = mock(DocumentMapper.class);
        documentVersionMapper = mock(DocumentVersionMapper.class);
        chunkMapper = mock(ChunkMapper.class);
        objectStorage = mock(ObjectStorage.class);
        parserClient = mock(DocumentParserClient.class);
        embeddingProvider = mock(EmbeddingProvider.class);
        chunkEmbedder = mock(ChunkEmbedder.class);
        chunkSplitter = mock(ChunkSplitter.class);
        taskLifecycle = mock(IndexTaskLifecycle.class);
        visionProvider = mock(VisionProvider.class);
        chunkIndexWriter = mock(ChunkIndexWriter.class);
        multimodalIndexManager = mock(MultimodalIndexManager.class);
        knowledgeBaseService = mock(KnowledgeBaseService.class);
        fingerprintFactory = mock(VersionFingerprintFactory.class);
        imageAssetService = mock(ImageAssetService.class);
        documentCleaner = mock(DocumentCleaner.class);
        pagedContentAssembler = mock(PagedContentAssembler.class);
        placeholderResolver = mock(ImagePlaceholderResolver.class);
        versionArtifactReuser = mock(VersionArtifactReuser.class);
        activationHandler = mock(VersionActivationHandler.class);

        document = document();
        version = version();
        config = new KbIndexConfig();
        task = task();

        when(documentVersionMapper.selectOne(any())).thenReturn(version);
        when(documentMapper.selectOne(any())).thenReturn(document);
        when(taskLifecycle.start(anyString(), any(TaskType.class))).thenReturn(task);
        when(knowledgeBaseService.indexConfigOf(KB_ID)).thenReturn(config);
        when(objectStorage.get(anyString())).thenAnswer(invocation -> stream(""));
        when(parserClient.parse(anyString(), anyString(), any()))
                .thenReturn(ParsedDocument.builder().markdown(MARKDOWN).build());
        when(imageAssetService.isStandaloneImage(anyString())).thenReturn(false);
        when(imageAssetService.materialize(any(), any(), any(), anyList())).thenReturn(List.of());
        when(imageAssetService.findByVersion(anyString())).thenReturn(List.of());
        when(documentCleaner.clean(anyString(), anyList(), any())).thenReturn(MARKDOWN);
        when(placeholderResolver.resolve(anyString(), any()))
                .thenReturn(new ProxiedContent(MARKDOWN, List.of()));
        when(fingerprintFactory.parseFingerprint(any(), any())).thenReturn("pf");
        when(fingerprintFactory.chunkFingerprint(any())).thenReturn("cf");
        when(visionProvider.model()).thenReturn("none");
        when(embeddingProvider.isConfigured()).thenReturn(false);
        when(embeddingProvider.model()).thenReturn("none");
        when(chunkSplitter.split(any())).thenReturn(List.of(chunk()));
        when(chunkEmbedder.embed(anyList())).thenReturn(Map.of());
        when(chunkMapper.selectList(any())).thenReturn(List.of());

        service = new IndexPipelineService(documentMapper, documentVersionMapper, chunkMapper, objectStorage,
                parserClient, embeddingProvider, chunkEmbedder, chunkSplitter, taskLifecycle, visionProvider,
                chunkIndexWriter, multimodalIndexManager, knowledgeBaseService, fingerprintFactory,
                imageAssetService, documentCleaner, pagedContentAssembler, placeholderResolver,
                versionArtifactReuser, activationHandler);
    }

    @Test
    void shouldSplitIndexAndActivateOnASuccessfulBuild() {
        service.execute(VERSION_ID);

        verify(chunkSplitter).split(any());
        verify(chunkIndexWriter).write(eq(KB_ID), anyList(), any());
        verify(activationHandler).activateAndFollowUp(document, version);
        verify(taskLifecycle).complete(task);
        verify(taskLifecycle, never()).fail(any(), anyString());
    }

    @Test
    void shouldParkTheDocumentWithoutIndexingWhenAParseConfirmationIsRequired() {
        config.setParsePreviewRequired(true);

        service.execute(VERSION_ID);

        // Nothing may reach the engines before a human confirms the preview, and the version must not be
        // activated either - the whole point of the pause is that the document is not yet searchable.
        verify(chunkSplitter, never()).split(any());
        verify(chunkIndexWriter, never()).write(anyString(), anyList(), any());
        verify(activationHandler, never()).activateAndFollowUp(any(), any());
        // The task itself succeeded: it did everything it was asked to do and is now waiting on a person.
        verify(taskLifecycle).complete(task);
        assertEquals(ProcessStatus.PENDING_CONFIRM, document.getProcessStatus());
    }

    @Test
    void shouldBuildFromTheCopiedGenerationWithoutCallingTheParserWhenChunkReuseSucceeds() {
        when(versionArtifactReuser.copyChunks(any(), any(), eq(SOURCE_VERSION_ID), anyBoolean()))
                .thenReturn(List.of(chunk()));

        service.execute(VERSION_ID, DocumentVersionPlanner.Reuse.chunks(SOURCE_VERSION_ID, PARSED_OBJECT));

        verify(parserClient, never()).parse(anyString(), anyString(), any());
        verify(chunkSplitter, never()).split(any());
        verify(activationHandler).activateAndFollowUp(document, version);
        verify(taskLifecycle).complete(task);
    }

    @Test
    void shouldFallBackToAFullBuildWhenTheReuseSourceCarriesNoChunk() {
        // The source may have been archived between the intake decision and this call. Trusting the copy
        // blindly would activate a version with zero chunks, which no caller could ever recall.
        when(versionArtifactReuser.copyChunks(any(), any(), eq(SOURCE_VERSION_ID), anyBoolean()))
                .thenReturn(List.of());

        service.execute(VERSION_ID, DocumentVersionPlanner.Reuse.chunks(SOURCE_VERSION_ID, PARSED_OBJECT));

        verify(chunkSplitter).split(any());
        verify(chunkIndexWriter).write(eq(KB_ID), anyList(), any());
        verify(activationHandler).activateAndFollowUp(document, version);
    }

    @Test
    void shouldReportAParseFailureAsParseFailedRatherThanIndexFailed() {
        when(parserClient.parse(anyString(), anyString(), any()))
                .thenThrow(new BizException(ErrorCode.PARSE_FAILED, "unreadable pdf"));

        service.execute(VERSION_ID);

        // The status is what sends an operator to one half of the system or the other.
        assertEquals(ProcessStatus.PARSE_FAILED, document.getProcessStatus());
        assertEquals(DocumentVersionStatus.BUILD_FAILED, version.getStatus());
        verify(taskLifecycle).fail(eq(task), anyString());
        verify(activationHandler, never()).activateAndFollowUp(any(), any());
    }

    @Test
    void shouldReportAnUnexpectedFailureAsIndexFailed() {
        when(chunkSplitter.split(any())).thenThrow(new IllegalStateException("splitter exploded"));

        service.execute(VERSION_ID);

        assertEquals(ProcessStatus.INDEX_FAILED, document.getProcessStatus());
        assertEquals(DocumentVersionStatus.BUILD_FAILED, version.getStatus());
        verify(taskLifecycle).fail(eq(task), anyString());
        verify(activationHandler, never()).activateAndFollowUp(any(), any());
    }

    @Test
    void shouldNotLetAFailedBuildEscapeToTheAsyncCaller() {
        when(chunkSplitter.split(any())).thenThrow(new IllegalStateException("splitter exploded"));

        // A throw here would leave the async wrapper to log a second, less informative failure over a
        // document whose state this method has already recorded correctly.
        service.execute(VERSION_ID);

        verify(taskLifecycle).fail(eq(task), anyString());
    }

    @Test
    void shouldSplitTheStoredPreviewOnConfirmWithoutParsingAgain() {
        when(objectStorage.get(anyString())).thenAnswer(invocation -> stream(JsonUtil.toJson(
                ParsePreview.builder().markdown(MARKDOWN).build())));

        service.confirm(VERSION_ID);

        // Re-deriving the text would let a configuration change made after the preview was rendered alter
        // what actually reaches the index - the operator would have approved something else.
        verify(parserClient, never()).parse(anyString(), anyString(), any());
        verify(documentCleaner, never()).clean(anyString(), anyList(), any());
        verify(chunkSplitter).split(any());
        verify(activationHandler).activateAndFollowUp(document, version);
        verify(taskLifecycle).complete(task);
    }

    @Test
    void shouldTellTheSplitterThatThePageStrategyApplies() {
        config.setSplitStrategy(PageSplitter.STRATEGY_CODE);

        service.execute(VERSION_ID);

        // One gate, read by two stages: the preparation decides whether to assemble page by page and the
        // splitter decides whether to dispatch to the page splitter. Two answers would silently degrade
        // the strategy to fixed length.
        assertTrue(capturedRequest().pageStrategy());
        assertFalse(capturedRequest().standaloneImage());
    }

    @Test
    void shouldNeverPageSplitAStandaloneImage() {
        config.setSplitStrategy(PageSplitter.STRATEGY_CODE);
        when(imageAssetService.isStandaloneImage(anyString())).thenReturn(true);
        when(imageAssetService.materializeStandalone(any(), any(), any())).thenReturn(imageAsset());

        service.execute(VERSION_ID);

        // An uploaded image has no parse artifact and its whole text comes from the vision model, so the
        // page route would find nothing and drop the one chunk the image needs.
        ChunkSplitter.SplitRequest request = capturedRequest();
        assertTrue(request.standaloneImage());
        assertFalse(request.pageStrategy());
        verify(parserClient, never()).parse(anyString(), anyString(), any());
    }

    private ChunkSplitter.SplitRequest capturedRequest() {
        ArgumentCaptor<ChunkSplitter.SplitRequest> captor =
                ArgumentCaptor.forClass(ChunkSplitter.SplitRequest.class);
        verify(chunkSplitter).split(captor.capture());
        return captor.getValue();
    }

    private static ByteArrayInputStream stream(String body) {
        return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
    }

    private static Document document() {
        Document document = new Document();
        document.setDocId(DOC_ID);
        document.setKbId(KB_ID);
        document.setFileName("guide.pdf");
        document.setFileExt("pdf");
        return document;
    }

    private static DocumentVersion version() {
        DocumentVersion version = new DocumentVersion();
        version.setVersionId(VERSION_ID);
        version.setDocId(DOC_ID);
        version.setMinioObject("kb/kb_test/doc/doc_test/source.pdf");
        return version;
    }

    private static KbTask task() {
        KbTask task = new KbTask();
        task.setTaskId("task_test");
        task.setTaskType(TaskType.INDEX);
        task.setBizId(VERSION_ID);
        return task;
    }

    private static Chunk chunk() {
        Chunk chunk = new Chunk();
        chunk.setChunkId("ck_1");
        chunk.setKbId(KB_ID);
        chunk.setDocId(DOC_ID);
        chunk.setDocumentVersionId(VERSION_ID);
        chunk.setContent(MARKDOWN);
        return chunk;
    }

    private static io.kbrag.domain.entity.ImageAsset imageAsset() {
        io.kbrag.domain.entity.ImageAsset asset = new io.kbrag.domain.entity.ImageAsset();
        asset.setImageId("img_1");
        asset.setObjectKey("kb/kb_test/doc/doc_test/dv_test/img_1.png");
        return asset;
    }
}
