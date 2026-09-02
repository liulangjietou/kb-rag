package io.kbrag.app.retrieval;

import io.kbrag.app.alert.RetrievalDegradeMonitor;
import io.kbrag.app.document.DocumentAclService;
import io.kbrag.app.graph.GraphRetrievalService;
import io.kbrag.app.graph.GraphRouteOutcome;
import io.kbrag.app.index.ActiveVersionResolver;
import io.kbrag.app.index.EngineChunkCleaner;
import io.kbrag.app.index.IndexAliasManager;
import io.kbrag.app.index.MultimodalIndexManager;
import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.entity.KnowledgeBase;
import io.kbrag.domain.enums.ChunkType;
import io.kbrag.domain.enums.DegradedReason;
import io.kbrag.domain.enums.EmbeddingStatus;
import io.kbrag.domain.enums.RetrievalSource;
import io.kbrag.domain.enums.ScoreType;
import io.kbrag.domain.mapper.ChunkMapper;
import io.kbrag.domain.mapper.DocumentMapper;
import io.kbrag.domain.model.FulltextQuery;
import io.kbrag.domain.model.KbIndexConfig;
import io.kbrag.domain.model.KbRef;
import io.kbrag.domain.model.GraphChunkRelevance;
import io.kbrag.domain.model.KbRetrievalConfig;
import io.kbrag.domain.model.MetadataFilter;
import io.kbrag.domain.model.ParentChildParams;
import io.kbrag.domain.model.RetrievalFilter;
import io.kbrag.domain.model.ScoredChunk;
import io.kbrag.domain.port.EmbeddingProvider;
import io.kbrag.domain.port.FulltextStore;
import io.kbrag.domain.port.MultimodalEmbeddingProvider;
import io.kbrag.domain.port.ObjectStorage;
import io.kbrag.domain.port.VectorStore;
import io.kbrag.domain.port.VisionProvider;
import io.kbrag.domain.service.CrossKbRrfFusion;
import io.kbrag.domain.service.FusionRouter;
import io.kbrag.domain.service.KbQuotaAllocator;
import io.kbrag.domain.service.ParentTextRedactor;
import io.kbrag.domain.service.RrfFusion;
import io.kbrag.domain.service.WeightedFusion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the assembled pipeline without touching a search engine: the dual route path, the zero key
 * single route path with its degradation marker, the parent merge, the threshold and the self healing
 * of engine hits the fact source no longer owns.
 *
 * @author owlzhangfq@gmail.com
 */
class RetrievalServiceTest {

    private static final String KB_ID = "kb_test";
    private static final String VERSION_ID = "dv_test";

    /** Parent chunk of the precise redaction cases; index 2 to 5 is the passage a disabled child covers. */
    private static final String PARENT_ID = "ck_parent";
    private static final String PARENT_TEXT = "甲乙丙丁戊己庚辛";
    private static final String FULLTEXT_ALIAS = "kb_test_es";
    private static final String VECTOR_ALIAS = "kb_test_es";
    private static final String SNAPSHOT_INDEX = "kb_test_none_s1";
    private static final String FROZEN_VERSION_ID = "dv_frozen";
    private static final String KB_ID_2 = "kb_second";
    private static final String FULLTEXT_ALIAS_2 = "kb_second_es";
    private static final String VECTOR_ALIAS_2 = "kb_second_vec";

    private KnowledgeBaseService knowledgeBaseService;
    private DocumentMapper documentMapper;
    private ChunkMapper chunkMapper;
    private FulltextStore fulltextStore;
    private VectorStore vectorStore;
    private EmbeddingProvider embeddingProvider;
    private IndexAliasManager indexAliasManager;
    private RoutingService routingService;
    private RewriteService rewriteService;
    private RerankService rerankService;
    private EngineChunkCleaner engineChunkCleaner;
    private ObjectStorage objectStorage;
    private KbProperties properties;
    private RetrievalIndexContextResolver indexContextResolver;
    private GraphRetrievalService graphRetrievalService;
    private MultimodalIndexManager multimodalIndexManager;
    private MultimodalEmbeddingProvider multimodalEmbeddingProvider;
    private VisionProvider visionProvider;
    private RetrievalService retrievalService;

    @BeforeEach
    void setUp() {
        knowledgeBaseService = mock(KnowledgeBaseService.class);
        documentMapper = mock(DocumentMapper.class);
        chunkMapper = mock(ChunkMapper.class);
        fulltextStore = mock(FulltextStore.class);
        vectorStore = mock(VectorStore.class);
        embeddingProvider = mock(EmbeddingProvider.class);
        indexAliasManager = mock(IndexAliasManager.class);
        routingService = mock(RoutingService.class);
        rewriteService = mock(RewriteService.class);
        rerankService = mock(RerankService.class);
        engineChunkCleaner = mock(EngineChunkCleaner.class);
        objectStorage = mock(ObjectStorage.class);
        graphRetrievalService = mock(GraphRetrievalService.class);
        multimodalIndexManager = mock(MultimodalIndexManager.class);
        multimodalEmbeddingProvider = mock(MultimodalEmbeddingProvider.class);
        visionProvider = mock(VisionProvider.class);
        properties = new KbProperties();

        when(knowledgeBaseService.require(KB_ID)).thenReturn(knowledgeBase(false));
        when(knowledgeBaseService.indexConfigOf(any(KnowledgeBase.class))).thenReturn(indexConfig(false));
        when(documentMapper.selectList(any())).thenReturn(List.of(document()));
        when(indexAliasManager.fulltextAlias(KB_ID)).thenReturn(FULLTEXT_ALIAS);
        when(indexAliasManager.vectorAlias(KB_ID)).thenReturn(VECTOR_ALIAS);
        when(routingService.route(anyList(), anyString(), anyBoolean(), any()))
                .thenAnswer(invocation -> RoutingOutcome.skipped(
                        ((List<KnowledgeBase>) invocation.getArgument(0)).stream()
                                .map(KnowledgeBase::getKbId).toList()));
        when(rewriteService.isAvailable()).thenReturn(false);
        when(rewriteService.rewrite(anyString(), any(), eq(false)))
                .thenAnswer(invocation -> RewriteOutcome.skipped(invocation.getArgument(0)));
        when(rerankService.isAvailable()).thenReturn(false);
        when(rerankService.candidateLimit()).thenReturn(50);
        when(rerankService.rerank(anyString(), anyList(), eq(false))).thenReturn(RerankOutcome.skipped());
        when(graphRetrievalService.recall(anyString(), any(), anyInt()))
                .thenReturn(GraphRouteOutcome.skipped());

        // A real context resolver over mocked collaborators rather than a mocked one: which index a base is
        // searched in and which versions it may see is the M6 decision under test in several cases below, and a
        // stubbed resolver would let that decision be asserted against itself. The ACL passes everything
        // through: document visibility trimming is covered by its own tests, not these.
        DocumentAclService documentAclService = mock(DocumentAclService.class);
        when(documentAclService.trimRestricted(anyString(), anyList()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        indexContextResolver = new RetrievalIndexContextResolver(indexAliasManager,
                new ActiveVersionResolver(documentMapper, properties), documentAclService,
                fulltextStore, vectorStore);
        // One policy instance for both collaborators, as the container wires it: the score a node reports
        // and the score the threshold acted on have to come from the same rules to stay comparable.
        ScoreThresholdPolicy scoreThresholdPolicy = new ScoreThresholdPolicy();
        retrievalService = new RetrievalService(knowledgeBaseService, chunkMapper,
                fulltextStore, vectorStore, embeddingProvider, indexContextResolver,
                new FusionRouter(List.of(new RrfFusion(), new WeightedFusion())),
                new CrossKbRrfFusion(), new KbQuotaAllocator(), graphRetrievalService,
                new MultimodalRetrievalService(multimodalIndexManager, multimodalEmbeddingProvider,
                        new ImageQueryService(visionProvider, properties), vectorStore),
                routingService,
                rewriteService, rerankService, scoreThresholdPolicy, new ParentChildMerger(),
                new NearDuplicateWindowMerger(),
                new DisabledChildVisibility(chunkMapper),
                new RetrievalNodeAssembler(chunkMapper, scoreThresholdPolicy, new ParentTextRedactor(),
                        objectStorage, properties),
                engineChunkCleaner,
                new RetrievalDegradeMonitor(properties), properties);
    }

    @Test
    void shouldEmbedTheAttachedImagesAndSteerTheMultimodalRouteWhenTheBaseCanSearchThem() {
        givenDualRoute();
        String mmAlias = "kb_test_mm";
        when(multimodalIndexManager.multimodalAlias(eq(KB_ID), any())).thenReturn(mmAlias);
        when(multimodalEmbeddingProvider.isConfigured()).thenReturn(true);
        when(multimodalEmbeddingProvider.embedImages(anyList()))
                .thenReturn(List.of(new float[]{0.5f, 0.6f}));
        when(vectorStore.search(eq(mmAlias), any()))
                .thenReturn(List.of(new ScoredChunk("ck_img", 0.88d, RetrievalSource.MM)));
        when(chunkMapper.selectList(any())).thenReturn(List.of(
                chunk("ck_1", "first chunk", null), chunk("ck_2", "second chunk", null),
                chunk("ck_img", "image chunk", null)));

        SearchOutcome outcome = retrievalService.search(KB_ID, command().images(List.of(image())).build());

        // The pictures were embedded and steered the multimodal route directly; the text embedding of the
        // query was never asked for, so this is image to image rather than a transcription.
        verify(multimodalEmbeddingProvider).embedImages(anyList());
        verify(multimodalEmbeddingProvider, never()).embedTexts(anyList());
        verify(vectorStore).search(eq(mmAlias), any());
        verify(visionProvider, never()).describeImage(any(), anyString());
        assertTrue(outcome.getNodes().stream().anyMatch(node -> "ck_img".equals(node.getChunkId())));
        assertTrue(outcome.getDegraded().isEmpty());
    }

    @Test
    void shouldFallBackToTheVisionTranscriptionWhenNoBaseCanSearchTheImages() {
        givenDualRoute();
        // No multimodal alias, so the corpus cannot embed images and the vision fallback transcribes them.
        when(multimodalIndexManager.multimodalAlias(eq(KB_ID), any())).thenReturn(null);
        when(multimodalEmbeddingProvider.isConfigured()).thenReturn(true);
        when(visionProvider.isConfigured()).thenReturn(true);
        when(visionProvider.describeImage(any(), anyString())).thenReturn("一张发票的照片");
        when(chunkMapper.selectList(any())).thenReturn(List.of(
                chunk("ck_1", "first chunk", null), chunk("ck_2", "second chunk", null)));

        retrievalService.search(KB_ID, command().images(List.of(image())).build());

        // The image text was folded into the query the pipeline ran with, and the multimodal embedding was
        // never asked for the pictures.
        verify(multimodalEmbeddingProvider, never()).embedImages(anyList());
        ArgumentCaptor<String> rewritten = ArgumentCaptor.forClass(String.class);
        verify(rewriteService).rewrite(rewritten.capture(), any(), eq(false));
        assertTrue(rewritten.getValue().contains("一张发票的照片"));
    }

    @Test
    void shouldRejectAnImageSetThatBreaksTheCountLimitBeforeEmbedding() {
        givenDualRoute();
        when(multimodalIndexManager.multimodalAlias(eq(KB_ID), any())).thenReturn("kb_test_mm");
        when(multimodalEmbeddingProvider.isConfigured()).thenReturn(true);
        List<String> tooMany = List.of(image(), image(), image(), image());

        // The image count and size gate is the same single point the vision transcription reuses, so an over
        // counted set is rejected before any embedding call whichever route it was headed for.
        assertThrows(BizException.class,
                () -> retrievalService.search(KB_ID, command().images(tooMany).build()));
        verify(multimodalEmbeddingProvider, never()).embedImages(anyList());
    }

    @Test
    void shouldRejectAnImageOnlyCallThatCarriesNoWrittenQuery() {
        givenDualRoute();
        when(multimodalIndexManager.multimodalAlias(eq(KB_ID), any())).thenReturn("kb_test_mm");
        when(multimodalEmbeddingProvider.isConfigured()).thenReturn(true);

        // A written query stays mandatory: a blank query is never routed to the multimodal space, it takes the
        // vision fallback whose gate rejects a call with nothing at all to search for.
        assertThrows(BizException.class,
                () -> retrievalService.search(KB_ID, command().query("  ").images(List.of(image())).build()));
        verify(multimodalEmbeddingProvider, never()).embedImages(anyList());
    }

    @Test
    void shouldFallBackToBm25WhenNoEmbeddingProviderIsConfigured() {
        when(embeddingProvider.isConfigured()).thenReturn(false);
        when(fulltextStore.searchBm25(anyString(), any()))
                .thenReturn(List.of(new ScoredChunk("ck_1", 7.5d, RetrievalSource.BM25)));
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk("ck_1", "knowledge base chunk", null)));

        SearchOutcome outcome = retrievalService.search(KB_ID, command().build());

        assertEquals(List.of(DegradedReason.VECTOR_ROUTE_UNAVAILABLE.code()), outcome.getDegraded());
        assertEquals(1, outcome.getNodes().size());
        RetrievalNodeView node = outcome.getNodes().get(0);
        assertEquals("ck_1", node.getChunkId());
        assertEquals(VERSION_ID, node.getDocumentVersionId());
        assertEquals(ScoreType.BM25_RANK.code(), node.getScoreType());
        assertEquals(RetrievalSource.BM25.code(), node.getRetrievalSource());
        assertEquals(7.5d, node.getScore());
        verify(vectorStore, never()).search(anyString(), any());
    }

    @Test
    void shouldFuseBothRoutesWhenEmbeddingProviderIsConfigured() {
        givenDualRoute();
        when(chunkMapper.selectList(any())).thenReturn(List.of(
                chunk("ck_1", "first chunk", null), chunk("ck_2", "second chunk", null)));

        SearchOutcome outcome = retrievalService.search(KB_ID, command().build());

        assertTrue(outcome.getDegraded().isEmpty());
        assertEquals(2, outcome.getNodes().size());
        RetrievalNodeView top = outcome.getNodes().get(0);
        assertEquals("ck_1", top.getChunkId());
        // No rerank and no threshold, so the fusion score is what ordered the list and what is reported.
        assertEquals(ScoreType.FUSED_RRF.code(), top.getScoreType());
        assertEquals(RetrievalSource.VECTOR.code(), top.getRetrievalSource());
        assertEquals(0.91d, top.getMetadata().get("vector_score"));
        assertEquals(4.0d, top.getMetadata().get("bm25_score"));
        assertTrue(top.getMetadata().containsKey("fused_score"));
        assertEquals("rrf", outcome.getApplied().getFusionMode());
        assertEquals("none", outcome.getApplied().getThresholdAppliedOn());
    }

    @Test
    void shouldForceTheVectorRouteOffForBm25OnlyEvenWhenEmbeddingIsConfigured() {
        // Requirement section 4.6: the evaluation runner's BM25_ONLY configuration must stay single
        // route once a zero key deployment gets an embedding provider, otherwise a four way comparison
        // could not tell "vector off" apart from "vector on" the moment a key is configured.
        givenDualRoute();
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk("ck_2", "bm25 hit", null)));

        SearchOutcome outcome = retrievalService.search(KB_ID,
                command().vectorRouteEnabled(false).build());

        assertEquals(1, outcome.getNodes().size());
        assertEquals("ck_2", outcome.getNodes().get(0).getChunkId());
        verify(vectorStore, never()).search(anyString(), any());
        // Explicitly turning a route off on purpose is not a degradation.
        assertFalse(outcome.getDegraded().contains(DegradedReason.VECTOR_ROUTE_UNAVAILABLE.code()));
    }

    @Test
    void shouldForceTheBm25RouteOffForVectorOnly() {
        givenDualRoute();
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk("ck_1", "vector hit", null)));

        SearchOutcome outcome = retrievalService.search(KB_ID,
                command().bm25RouteEnabled(false).build());

        assertEquals(1, outcome.getNodes().size());
        assertEquals("ck_1", outcome.getNodes().get(0).getChunkId());
        verify(fulltextStore, never()).searchBm25(anyString(), any());
    }

    @Test
    void shouldExposeNormalisedScoresInWeightedMode() {
        givenDualRoute();
        when(chunkMapper.selectList(any())).thenReturn(List.of(
                chunk("ck_1", "first chunk", null), chunk("ck_2", "second chunk", null)));

        SearchOutcome outcome = retrievalService.search(KB_ID,
                command().fusionMode("weighted").wVec(0.7d).build());

        assertEquals("weighted", outcome.getApplied().getFusionMode());
        Map<String, Object> metadata = outcome.getNodes().get(0).getMetadata();
        assertTrue(metadata.containsKey("norm_vector_score"));
        assertTrue(metadata.containsKey("norm_bm25_score"));
        // The raw scores travel alongside the normalised ones, otherwise a ranking cannot be explained.
        assertTrue(metadata.containsKey("vector_score"));
        assertTrue(metadata.containsKey("bm25_score"));
    }

    @Test
    void shouldMarkTheThresholdInactiveOnASingleBm25Route() {
        when(embeddingProvider.isConfigured()).thenReturn(false);
        when(fulltextStore.searchBm25(anyString(), any()))
                .thenReturn(List.of(new ScoredChunk("ck_1", 7.5d, RetrievalSource.BM25)));
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk("ck_1", "only chunk", null)));

        SearchOutcome outcome = retrievalService.search(KB_ID, command().scoreThreshold(0.5d).build());

        assertTrue(outcome.getDegraded().contains(DegradedReason.THRESHOLD_INACTIVE.code()));
        // Inactive means nothing was filtered, not that everything was filtered out.
        assertEquals(1, outcome.getNodes().size());
        assertEquals("none", outcome.getApplied().getThresholdAppliedOn());
    }

    @Test
    void shouldFilterOnCosineWhenRerankIsUnavailable() {
        when(embeddingProvider.isConfigured()).thenReturn(true);
        when(embeddingProvider.embed(any())).thenReturn(List.of(new float[]{0.1f, 0.2f}));
        when(fulltextStore.searchBm25(anyString(), any())).thenReturn(List.of());
        when(vectorStore.search(anyString(), any())).thenReturn(List.of(
                new ScoredChunk("ck_high", 0.91d, RetrievalSource.VECTOR),
                new ScoredChunk("ck_low", 0.30d, RetrievalSource.VECTOR)));
        when(chunkMapper.selectList(any())).thenReturn(List.of(
                chunk("ck_high", "relevant", null), chunk("ck_low", "unrelated", null)));

        SearchOutcome outcome = retrievalService.search(KB_ID, command().scoreThreshold(0.5d).build());

        assertEquals(1, outcome.getNodes().size());
        assertEquals("ck_high", outcome.getNodes().get(0).getChunkId());
        assertEquals(ScoreType.COSINE.code(), outcome.getNodes().get(0).getScoreType());
        assertEquals(ScoreType.COSINE.code(), outcome.getApplied().getThresholdAppliedOn());
        assertFalse(outcome.getDegraded().contains(DegradedReason.THRESHOLD_INACTIVE.code()));
    }

    @Test
    void shouldMergeChildrenIntoTheirParent() {
        when(knowledgeBaseService.indexConfigOf(any(KnowledgeBase.class))).thenReturn(indexConfig(true));
        when(embeddingProvider.isConfigured()).thenReturn(false);
        when(fulltextStore.searchBm25(anyString(), any())).thenReturn(List.of(
                new ScoredChunk("ck_child_a", 9.0d, RetrievalSource.BM25),
                new ScoredChunk("ck_child_b", 4.0d, RetrievalSource.BM25)));
        // Three reads in order: the recalled children, the disabled children of the merged parent, and
        // the parent row whose text is returned.
        when(chunkMapper.selectList(any()))
                .thenReturn(List.of(
                        chunk("ck_child_a", "first passage", "ck_parent"),
                        chunk("ck_child_b", "second passage", "ck_parent")))
                .thenReturn(List.of())
                .thenReturn(List.of(chunk("ck_parent", "the whole section", null)));

        SearchOutcome outcome = retrievalService.search(KB_ID, command().build());

        assertEquals(1, outcome.getNodes().size());
        RetrievalNodeView node = outcome.getNodes().get(0);
        // The caller receives the parent text while the engines only ever saw the children.
        assertEquals("ck_parent", node.getChunkId());
        assertEquals("the whole section", node.getContent());
        assertEquals(List.of("ck_child_a", "ck_child_b"), node.getMetadata().get("child_ids"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> children = (List<Map<String, Object>>) node.getMetadata().get("children");
        assertEquals(2, children.size());
        assertEquals("ck_child_a", children.get(0).get("chunk_id"));
        assertEquals("first passage", children.get(0).get("content"));
        assertTrue(children.get(0).containsKey("score"));
        assertTrue(children.get(0).containsKey("score_type"));
        assertTrue(children.get(0).containsKey("bm25_score"));
    }

    @Test
    void shouldScheduleCleanupForEngineHitsWithoutAFactSourceRow() {
        when(embeddingProvider.isConfigured()).thenReturn(false);
        when(fulltextStore.searchBm25(anyString(), any())).thenReturn(List.of(
                new ScoredChunk("ck_live", 9.0d, RetrievalSource.BM25),
                new ScoredChunk("ck_orphan", 8.0d, RetrievalSource.BM25)));
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk("ck_live", "still here", null)));

        SearchOutcome outcome = retrievalService.search(KB_ID, command().build());

        assertEquals(1, outcome.getNodes().size());
        verify(engineChunkCleaner, times(1)).removeAsync(KB_ID, List.of("ck_orphan"));
    }

    @Test
    void shouldPushTheMetadataFilterDownToBothRoutes() {
        givenDualRoute();
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk("ck_1", "first chunk", null)));
        MetadataFilter metadataFilter = MetadataFilter.builder()
                .sessionId("session_1")
                .msgTimeFrom(100L)
                .msgTimeTo(200L)
                .build();

        retrievalService.search(KB_ID, command().metadataFilter(metadataFilter).build());

        ArgumentCaptor<FulltextQuery> bm25 = ArgumentCaptor.forClass(FulltextQuery.class);
        verify(fulltextStore).searchBm25(anyString(), bm25.capture());
        RetrievalFilter filter = bm25.getValue().getFilter();
        // Pushed down, never applied after recall: post filtering would shrink the candidate set below
        // recall_top_k and bias the fusion stage.
        assertEquals("session_1", filter.getMetadataFilter().getSessionId());
        assertEquals(List.of(VERSION_ID), filter.getDocumentVersionIds());
        assertTrue(filter.isEnabledOnly());
    }

    @Test
    void shouldSearchWithTheRewrittenQuery() {
        when(rewriteService.isAvailable()).thenReturn(true);
        when(rewriteService.rewrite(anyString(), any(), eq(true)))
                .thenReturn(RewriteOutcome.rewritten("product X price list"));
        when(embeddingProvider.isConfigured()).thenReturn(false);
        when(fulltextStore.searchBm25(anyString(), any())).thenReturn(List.of());

        SearchOutcome outcome = retrievalService.search(KB_ID, command().rewriteEnabled(true).build());

        ArgumentCaptor<FulltextQuery> bm25 = ArgumentCaptor.forClass(FulltextQuery.class);
        verify(fulltextStore).searchBm25(anyString(), bm25.capture());
        assertEquals("product X price list", bm25.getValue().getQueryText());
        assertEquals("product X price list", outcome.getApplied().getRewriteUsedQuery());
    }

    @Test
    void shouldReturnNothingWhenTheKnowledgeBaseHasNoActiveVersion() {
        when(documentMapper.selectList(any())).thenReturn(List.of());

        SearchOutcome outcome = retrievalService.search(KB_ID, command().build());

        assertTrue(outcome.getNodes().isEmpty());
        verify(fulltextStore, never()).searchBm25(anyString(), any());
    }

    @Test
    void shouldTruncateToTopN() {
        when(embeddingProvider.isConfigured()).thenReturn(false);
        when(fulltextStore.searchBm25(anyString(), any())).thenReturn(List.of(
                new ScoredChunk("ck_1", 9.0d, RetrievalSource.BM25),
                new ScoredChunk("ck_2", 8.0d, RetrievalSource.BM25),
                new ScoredChunk("ck_3", 7.0d, RetrievalSource.BM25)));
        when(chunkMapper.selectList(any())).thenReturn(List.of(
                chunk("ck_1", "one", null), chunk("ck_2", "two", null), chunk("ck_3", "three", null)));

        SearchOutcome outcome = retrievalService.search(KB_ID, command().topN(2).build());

        assertEquals(2, outcome.getNodes().size());
        assertEquals(List.of("ck_1", "ck_2"),
                outcome.getNodes().stream().map(RetrievalNodeView::getChunkId).toList());
    }

    @Test
    void shouldReturnOneNodeForTwoOverlappingChatWindows() {
        when(embeddingProvider.isConfigured()).thenReturn(false);
        when(fulltextStore.searchBm25(anyString(), any())).thenReturn(List.of(
                new ScoredChunk("ck_w1", 9.0d, RetrievalSource.BM25),
                new ScoredChunk("ck_w2", 4.0d, RetrievalSource.BM25)));
        when(chunkMapper.selectList(any())).thenReturn(List.of(
                chatWindow("ck_w1", 0, 3), chatWindow("ck_w2", 2, 5)));

        SearchOutcome outcome = retrievalService.search(KB_ID, command().build());

        assertEquals(1, outcome.getNodes().size());
        RetrievalNodeView node = outcome.getNodes().get(0);
        assertEquals("ck_w1", node.getChunkId());
        assertEquals(List.of("ck_w2"), node.getMetadata().get("merged_window_chunk_ids"));
        // The stored window facts still travel to the caller: the debug page names the window a result
        // came from, and the merged list is only readable next to them.
        assertEquals(0, node.getMetadata().get("window_seq"));
        assertEquals(List.of(0, 3), node.getMetadata().get("msg_span"));
    }

    @Test
    void shouldNotMergeChatWindowsThatShareTooLittle() {
        when(embeddingProvider.isConfigured()).thenReturn(false);
        when(fulltextStore.searchBm25(anyString(), any())).thenReturn(List.of(
                new ScoredChunk("ck_w1", 9.0d, RetrievalSource.BM25),
                new ScoredChunk("ck_w2", 4.0d, RetrievalSource.BM25)));
        when(chunkMapper.selectList(any())).thenReturn(List.of(
                chatWindow("ck_w1", 0, 3), chatWindow("ck_w2", 3, 6)));

        SearchOutcome outcome = retrievalService.search(KB_ID, command().build());

        assertEquals(2, outcome.getNodes().size());
        assertFalse(outcome.getNodes().get(0).getMetadata().containsKey("merged_window_chunk_ids"));
    }

    @Test
    void shouldLeaveASearchWithoutChatWindowsUntouched() {
        givenDualRoute();
        when(chunkMapper.selectList(any())).thenReturn(List.of(
                chunk("ck_1", "first chunk", null), chunk("ck_2", "second chunk", null)));

        SearchOutcome outcome = retrievalService.search(KB_ID, command().build());

        // A chunk without a message span can neither absorb nor be absorbed, so a knowledge base holding
        // no chat import is provably unaffected by the stage.
        assertEquals(2, outcome.getNodes().size());
        assertFalse(outcome.getNodes().get(0).getMetadata().containsKey("merged_window_chunk_ids"));
        assertFalse(outcome.getNodes().get(1).getMetadata().containsKey("merged_window_chunk_ids"));
    }

    @Test
    void shouldDropNearDuplicateWindowsBeforeTheyReachTheRerankStage() {
        when(embeddingProvider.isConfigured()).thenReturn(false);
        when(rerankService.isAvailable()).thenReturn(true);
        when(rerankService.rerank(anyString(), anyList(), eq(true)))
                .thenAnswer(invocation -> RerankOutcome.applied(
                        ((List<String>) invocation.getArgument(1)).stream().map(text -> 1.0d).toList()));
        when(fulltextStore.searchBm25(anyString(), any())).thenReturn(List.of(
                new ScoredChunk("ck_w1", 9.0d, RetrievalSource.BM25),
                new ScoredChunk("ck_w2", 4.0d, RetrievalSource.BM25)));
        when(chunkMapper.selectList(any())).thenReturn(List.of(
                chatWindow("ck_w1", 0, 3), chatWindow("ck_w2", 2, 5)));

        retrievalService.search(KB_ID, command().rerankEnabled(true).build());

        // The duplicate never reaches the cross encoder: reranking it would pay twice for one passage and
        // the parent merge that follows would then be grouping a candidate that is already redundant.
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(rerankService).rerank(anyString(), captor.capture(), eq(true));
        assertEquals(1, captor.getValue().size());
    }

    @Test
    void shouldMergeNearDuplicateWindowsBeforeTheParentMerge() {
        when(knowledgeBaseService.require(KB_ID)).thenReturn(knowledgeBase(true));
        when(knowledgeBaseService.indexConfigOf(any(KnowledgeBase.class))).thenReturn(indexConfig(true));
        when(embeddingProvider.isConfigured()).thenReturn(false);
        when(fulltextStore.searchBm25(anyString(), any())).thenReturn(List.of(
                new ScoredChunk("ck_w1", 9.0d, RetrievalSource.BM25),
                new ScoredChunk("ck_w2", 4.0d, RetrievalSource.BM25)));
        Chunk first = chatWindow("ck_w1", 0, 3);
        Chunk second = chatWindow("ck_w2", 2, 5);
        first.setParentId("ck_parent");
        second.setParentId("ck_parent");
        when(chunkMapper.selectList(any())).thenReturn(List.of(first, second,
                chunk("ck_parent", "the whole conversation", null)));

        SearchOutcome outcome = retrievalService.search(KB_ID, command().build());

        // The two windows share a parent, so the parent merge alone would have grouped them and reported
        // both as matched children. The near duplicate stage runs first, so only the surviving window is
        // ever a member - the two reductions answer different questions and are applied in this order.
        assertEquals(1, outcome.getNodes().size());
        assertEquals(List.of("ck_w1"), outcome.getNodes().get(0).getMetadata().get("child_ids"));
        assertEquals(List.of("ck_w2"),
                outcome.getNodes().get(0).getMetadata().get("merged_window_chunk_ids"));
    }

    @Test
    void shouldSearchEveryLinkedBaseAndReportThemInTheAppliedBlock() {
        givenTwoBases();
        when(fulltextStore.searchBm25(eq(FULLTEXT_ALIAS), any()))
                .thenReturn(List.of(new ScoredChunk("ck_a1", 9.0d, RetrievalSource.BM25)));
        when(fulltextStore.searchBm25(eq(FULLTEXT_ALIAS_2), any()))
                .thenReturn(List.of(new ScoredChunk("ck_b1", 3.0d, RetrievalSource.BM25)));
        when(chunkMapper.selectList(any())).thenReturn(List.of(
                chunk("ck_a1", "manual passage", null, KB_ID),
                chunk("ck_b1", "chat passage", null, KB_ID_2)));

        SearchOutcome outcome = retrievalService.search(twoRefs(1, 1), command().build());

        assertEquals(List.of(KB_ID, KB_ID_2), outcome.getApplied().getRoutedKbIds());
        assertEquals(List.of("ck_a1", "ck_b1"),
                outcome.getNodes().stream().map(RetrievalNodeView::getChunkId).toList());
        // A node has to be traceable to its base, and the value comes from the fact source row rather than
        // from the routing decision so it cannot disagree with where the text actually lives.
        assertEquals(KB_ID, outcome.getNodes().get(0).getMetadata().get("kb_id"));
        assertEquals(KB_ID_2, outcome.getNodes().get(1).getMetadata().get("kb_id"));
    }

    @Test
    void shouldOnlySearchTheBasesTheRouterSelected() {
        givenTwoBases();
        when(routingService.route(anyList(), anyString(), anyBoolean(), any()))
                .thenReturn(RoutingOutcome.routed(List.of(KB_ID_2)));
        when(fulltextStore.searchBm25(eq(FULLTEXT_ALIAS_2), any()))
                .thenReturn(List.of(new ScoredChunk("ck_b1", 3.0d, RetrievalSource.BM25)));
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk("ck_b1", "chat passage", null, KB_ID_2)));

        SearchOutcome outcome = retrievalService.search(twoRefs(1, 1), command().routingEnabled(true).build());

        assertEquals(List.of(KB_ID_2), outcome.getApplied().getRoutedKbIds());
        assertEquals(List.of("ck_b1"),
                outcome.getNodes().stream().map(RetrievalNodeView::getChunkId).toList());
        // The discarded base must not be queried at all: the whole benefit of routing is the round trip and
        // the candidates it never has to compete with.
        verify(fulltextStore, never()).searchBm25(eq(FULLTEXT_ALIAS), any());
    }

    @Test
    void shouldCarryTheRouterFallbackMarkerAndStillSearchEveryBase() {
        givenTwoBases();
        when(routingService.route(anyList(), anyString(), anyBoolean(), any()))
                .thenReturn(RoutingOutcome.fallbackAll(List.of(KB_ID, KB_ID_2)));
        when(fulltextStore.searchBm25(eq(FULLTEXT_ALIAS), any()))
                .thenReturn(List.of(new ScoredChunk("ck_a1", 9.0d, RetrievalSource.BM25)));
        when(fulltextStore.searchBm25(eq(FULLTEXT_ALIAS_2), any())).thenReturn(List.of());
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk("ck_a1", "manual passage", null, KB_ID)));

        SearchOutcome outcome = retrievalService.search(twoRefs(1, 1), command().routingEnabled(true).build());

        assertTrue(outcome.getDegraded().contains(DegradedReason.ROUTE_FALLBACK_ALL.code()));
        assertEquals(List.of(KB_ID, KB_ID_2), outcome.getApplied().getRoutedKbIds());
        verify(fulltextStore).searchBm25(eq(FULLTEXT_ALIAS), any());
        verify(fulltextStore).searchBm25(eq(FULLTEXT_ALIAS_2), any());
    }

    @Test
    void shouldInterleaveTheBasesByTheirInBaseRank() {
        givenTwoBases();
        // The second base scores an order of magnitude higher, which is what two different embedding models
        // or two different corpora produce. Cross base ordering must not notice.
        when(fulltextStore.searchBm25(eq(FULLTEXT_ALIAS), any())).thenReturn(List.of(
                new ScoredChunk("ck_x1", 0.5d, RetrievalSource.BM25),
                new ScoredChunk("ck_x2", 0.4d, RetrievalSource.BM25)));
        when(fulltextStore.searchBm25(eq(FULLTEXT_ALIAS_2), any())).thenReturn(List.of(
                new ScoredChunk("ck_y1", 99.0d, RetrievalSource.BM25),
                new ScoredChunk("ck_y2", 98.0d, RetrievalSource.BM25)));
        when(chunkMapper.selectList(any())).thenReturn(List.of(
                chunk("ck_x1", "one", null, KB_ID), chunk("ck_x2", "two", null, KB_ID),
                chunk("ck_y1", "three", null, KB_ID_2), chunk("ck_y2", "four", null, KB_ID_2)));

        SearchOutcome outcome = retrievalService.search(twoRefs(1, 1), command().topN(10).build());

        assertEquals(List.of("ck_x1", "ck_y1", "ck_x2", "ck_y2"),
                outcome.getNodes().stream().map(RetrievalNodeView::getChunkId).toList());
        // Fusion mode reported is the cross base one, so the score type a node carries stays honest.
        assertEquals("rrf", outcome.getApplied().getFusionMode());
    }

    @Test
    void shouldCutEachBaseToItsWeightedQuotaBeforeMerging() {
        givenTwoBases();
        // A four candidate budget split three to one: 4*3/4 = 3 and 4*1/4 = 1, nothing left over.
        when(rerankService.candidateLimit()).thenReturn(4);
        when(fulltextStore.searchBm25(eq(FULLTEXT_ALIAS), any())).thenReturn(List.of(
                new ScoredChunk("ck_a1", 9.0d, RetrievalSource.BM25),
                new ScoredChunk("ck_a2", 8.0d, RetrievalSource.BM25),
                new ScoredChunk("ck_a3", 7.0d, RetrievalSource.BM25),
                new ScoredChunk("ck_a4", 6.0d, RetrievalSource.BM25)));
        when(fulltextStore.searchBm25(eq(FULLTEXT_ALIAS_2), any())).thenReturn(List.of(
                new ScoredChunk("ck_b1", 9.0d, RetrievalSource.BM25),
                new ScoredChunk("ck_b2", 8.0d, RetrievalSource.BM25),
                new ScoredChunk("ck_b3", 7.0d, RetrievalSource.BM25)));
        when(chunkMapper.selectList(any())).thenReturn(List.of(
                chunk("ck_a1", "a1", null, KB_ID), chunk("ck_a2", "a2", null, KB_ID),
                chunk("ck_a3", "a3", null, KB_ID), chunk("ck_a4", "a4", null, KB_ID),
                chunk("ck_b1", "b1", null, KB_ID_2), chunk("ck_b2", "b2", null, KB_ID_2),
                chunk("ck_b3", "b3", null, KB_ID_2)));

        SearchOutcome outcome = retrievalService.search(twoRefs(3, 1), command().topN(10).build());

        assertEquals(List.of("ck_a1", "ck_b1", "ck_a2", "ck_a3"),
                outcome.getNodes().stream().map(RetrievalNodeView::getChunkId).toList());
    }

    @Test
    void shouldEmbedTheQueryOnceHoweverManyBasesAreSearched() {
        givenTwoBases();
        when(embeddingProvider.isConfigured()).thenReturn(true);
        when(embeddingProvider.embed(any())).thenReturn(List.of(new float[]{0.1f, 0.2f}));
        when(fulltextStore.searchBm25(anyString(), any())).thenReturn(List.of());
        when(vectorStore.search(anyString(), any())).thenReturn(List.of());

        retrievalService.search(twoRefs(1, 1), command().build());

        // Every base is asked the same question; embedding once per base would pay N times for one vector.
        verify(embeddingProvider, times(1)).embed(any());
        verify(vectorStore).search(eq(VECTOR_ALIAS), any());
        verify(vectorStore).search(eq(VECTOR_ALIAS_2), any());
    }

    @Test
    void shouldRejectASearchWithoutAnyKnowledgeBase() {
        assertThrows(BizException.class, () -> retrievalService.search(List.of(), command().build()));
    }

    @Test
    void shouldStillSearchTheOtherBaseWhenOneHoldsNoActiveVersion() {
        givenTwoBases();
        when(documentMapper.selectList(any())).thenReturn(List.of(document()), List.of());
        when(fulltextStore.searchBm25(eq(FULLTEXT_ALIAS), any()))
                .thenReturn(List.of(new ScoredChunk("ck_a1", 9.0d, RetrievalSource.BM25)));
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk("ck_a1", "manual passage", null, KB_ID)));

        SearchOutcome outcome = retrievalService.search(twoRefs(1, 1), command().build());

        assertEquals(List.of("ck_a1"),
                outcome.getNodes().stream().map(RetrievalNodeView::getChunkId).toList());
        // A base with nothing indexed is not queried, and it also does not reserve any of the quota.
        verify(fulltextStore, never()).searchBm25(eq(FULLTEXT_ALIAS_2), any());
    }

    @Test
    void shouldSearchTheFrozenSnapshotIndexForAReleasedVersion() {
        when(embeddingProvider.isConfigured()).thenReturn(false);
        givenSnapshotIndexPresent();
        when(fulltextStore.searchBm25(eq(SNAPSHOT_INDEX), any()))
                .thenReturn(List.of(new ScoredChunk("ck_frozen", 7.5d, RetrievalSource.BM25)));
        when(chunkMapper.selectList(any()))
                .thenReturn(List.of(chunk("ck_frozen", "corpus as released", null)));

        SearchOutcome outcome = retrievalService.search(KB_ID, snapshotCommand().build());

        assertTrue(outcome.getDegraded().stream()
                .noneMatch(marker -> marker.equals(DegradedReason.SNAPSHOT_INDEX_MISSING.code())));
        assertEquals(List.of("ck_frozen"),
                outcome.getNodes().stream().map(RetrievalNodeView::getChunkId).toList());
        // The live alias is never touched, and the mandatory filter carries the frozen set rather than the
        // current active version.
        verify(fulltextStore, never()).searchBm25(eq(FULLTEXT_ALIAS), any());
        ArgumentCaptor<FulltextQuery> query = ArgumentCaptor.forClass(FulltextQuery.class);
        verify(fulltextStore).searchBm25(eq(SNAPSHOT_INDEX), query.capture());
        assertEquals(List.of(FROZEN_VERSION_ID), query.getValue().getFilter().getDocumentVersionIds());
    }

    @Test
    void shouldDegradeToTheLiveAliasWhenTheFrozenSnapshotIndexIsMissing() {
        when(embeddingProvider.isConfigured()).thenReturn(false);
        when(fulltextStore.indexExists(SNAPSHOT_INDEX)).thenReturn(false);
        when(fulltextStore.searchBm25(eq(FULLTEXT_ALIAS), any()))
                .thenReturn(List.of(new ScoredChunk("ck_1", 7.5d, RetrievalSource.BM25)));
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk("ck_1", "current corpus", null)));

        SearchOutcome outcome = retrievalService.search(KB_ID, snapshotCommand().build());

        assertTrue(outcome.getDegraded().contains(DegradedReason.SNAPSHOT_INDEX_MISSING.code()));
        // Degraded but useful: the result really does come out of the live alias under the current active
        // versions, not out of an error.
        assertEquals(List.of("ck_1"),
                outcome.getNodes().stream().map(RetrievalNodeView::getChunkId).toList());
        ArgumentCaptor<FulltextQuery> query = ArgumentCaptor.forClass(FulltextQuery.class);
        verify(fulltextStore).searchBm25(eq(FULLTEXT_ALIAS), query.capture());
        assertEquals(List.of(VERSION_ID), query.getValue().getFilter().getDocumentVersionIds());
    }

    @Test
    void shouldNotReportALegacyReleaseWithoutASnapshotAsDegraded() {
        when(embeddingProvider.isConfigured()).thenReturn(false);
        when(fulltextStore.searchBm25(eq(FULLTEXT_ALIAS), any()))
                .thenReturn(List.of(new ScoredChunk("ck_1", 7.5d, RetrievalSource.BM25)));
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk("ck_1", "current corpus", null)));

        SearchOutcome outcome = retrievalService.search(KB_ID,
                command().vectorRouteEnabled(false).build());

        // A version released before index snapshots existed carries no override at all. Serving it from the
        // live alias is its designed behaviour, so the response must stay clean.
        assertTrue(outcome.getDegraded().isEmpty());
        assertEquals(1, outcome.getNodes().size());
    }

    @Test
    void shouldNeverSelfHealEngineHitsRecalledFromASnapshot() {
        when(embeddingProvider.isConfigured()).thenReturn(false);
        givenSnapshotIndexPresent();
        when(fulltextStore.searchBm25(eq(SNAPSHOT_INDEX), any())).thenReturn(List.of(
                new ScoredChunk("ck_live", 9.0d, RetrievalSource.BM25),
                new ScoredChunk("ck_gone", 8.0d, RetrievalSource.BM25)));
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk("ck_live", "still here", null)));

        SearchOutcome outcome = retrievalService.search(KB_ID, snapshotCommand().build());

        // A snapshot legitimately holds chunks MySQL no longer has - merged, split or cleaned up after the
        // release. Repairing by chunk id would delete those ids from the live indexes on the strength of what
        // an old snapshot contains, which is data loss in an index nobody searched.
        assertEquals(List.of("ck_live"),
                outcome.getNodes().stream().map(RetrievalNodeView::getChunkId).toList());
        verify(engineChunkCleaner, never()).removeAsync(anyString(), anyList());
    }


    @Test
    void shouldFuseTheGraphRouteAsTheThirdRouteOfTheInBaseRanking() {
        givenGraphEnabledBase();
        givenDualRoute();
        givenGraphRoute(List.of(
                new ScoredChunk("ck_3", 0.4d, RetrievalSource.GRAPH),
                new ScoredChunk("ck_1", 0.3d, RetrievalSource.GRAPH)),
                Map.of("ck_3", new GraphChunkRelevance("ck_3", 0.4d, 1, List.of("Neo4j")),
                        "ck_1", new GraphChunkRelevance("ck_1", 0.3d, 2, List.of("A", "B"))));
        when(chunkMapper.selectList(any())).thenReturn(List.of(
                chunk("ck_1", "first chunk", null), chunk("ck_2", "second chunk", null),
                chunk("ck_3", "graph only chunk", null)));

        SearchOutcome outcome = retrievalService.search(KB_ID, command().build());

        // Reciprocal rank fusion over three lists: ck_1 is in all three, ck_2 and ck_3 lead one list each
        // and tie on 1/(60+1), so the chunk id breaks the tie. ck_3 is reachable through the graph alone,
        // which is what proves the third route took part in the in base fusion rather than being appended.
        assertEquals(List.of("ck_1", "ck_2", "ck_3"),
                outcome.getNodes().stream().map(RetrievalNodeView::getChunkId).toList());
        assertEquals(RetrievalSource.GRAPH.code(),
                outcome.getNodes().get(2).getRetrievalSource());
        assertTrue(outcome.getDegraded().isEmpty());
        assertEquals("rrf", outcome.getApplied().getFusionMode());
    }

    @Test
    void shouldExposeTheGraphDetailOnTheNodesTheGraphRouteReached() {
        givenGraphEnabledBase();
        givenDualRoute();
        givenGraphRoute(List.of(new ScoredChunk("ck_1", 0.4d, RetrievalSource.GRAPH)),
                Map.of("ck_1", new GraphChunkRelevance("ck_1", 0.4d, 1, List.of("苹果公司", "乔布斯"))));
        when(chunkMapper.selectList(any())).thenReturn(List.of(
                chunk("ck_1", "first chunk", null), chunk("ck_2", "second chunk", null)));

        SearchOutcome outcome = retrievalService.search(KB_ID, command().build());

        Map<String, Object> reached = outcome.getNodes().get(0).getMetadata();
        assertEquals("ck_1", outcome.getNodes().get(0).getChunkId());
        assertEquals(0.4d, reached.get("graph_score"));
        assertEquals(1, reached.get("graph_hops"));
        assertEquals(List.of("苹果公司", "乔布斯"), reached.get("graph_entities"));
        // A node the graph never reached carries none of the three keys, so a debug page can tell the
        // two apart instead of reading a zero it cannot interpret.
        Map<String, Object> untouched = outcome.getNodes().get(1).getMetadata();
        assertFalse(untouched.containsKey("graph_score"));
        assertFalse(untouched.containsKey("graph_hops"));
        assertFalse(untouched.containsKey("graph_entities"));
    }

    @Test
    void shouldDegradeToTheOtherTwoRoutesWhenTheGraphCannotBeReached() {
        givenGraphEnabledBase();
        givenDualRoute();
        when(graphRetrievalService.recall(anyString(), any(), anyInt()))
                .thenReturn(GraphRouteOutcome.degraded(DegradedReason.GRAPH_ROUTE_UNAVAILABLE.code()));
        when(chunkMapper.selectList(any())).thenReturn(List.of(
                chunk("ck_1", "first chunk", null), chunk("ck_2", "second chunk", null)));

        SearchOutcome outcome = retrievalService.search(KB_ID, command().build());

        assertEquals(List.of(DegradedReason.GRAPH_ROUTE_UNAVAILABLE.code()), outcome.getDegraded());
        // The two engine routes are untouched: the graph points into a corpus, it does not hold it.
        assertEquals(List.of("ck_1", "ck_2"),
                outcome.getNodes().stream().map(RetrievalNodeView::getChunkId).toList());
        assertEquals(0.91d, outcome.getNodes().get(0).getMetadata().get("vector_score"));
    }

    @Test
    void shouldSwitchTheGraphRouteOffOnASnapshotContextWithoutReportingADegradation() {
        givenGraphEnabledBase();
        when(embeddingProvider.isConfigured()).thenReturn(false);
        givenSnapshotIndexPresent();
        when(fulltextStore.searchBm25(eq(SNAPSHOT_INDEX), any()))
                .thenReturn(List.of(new ScoredChunk("ck_frozen", 7.5d, RetrievalSource.BM25)));
        when(chunkMapper.selectList(any()))
                .thenReturn(List.of(chunk("ck_frozen", "corpus as released", null)));

        SearchOutcome outcome = retrievalService.search(KB_ID, snapshotCommand().build());

        // The graph only ever holds the active version, so a released version has nothing snapshot shaped
        // to search. That is a capability boundary of the release contract, not a fault.
        verify(graphRetrievalService, never()).recall(anyString(), any(), anyInt());
        assertFalse(outcome.getDegraded().contains(DegradedReason.GRAPH_ROUTE_UNAVAILABLE.code()));
        assertEquals(List.of("ck_frozen"),
                outcome.getNodes().stream().map(RetrievalNodeView::getChunkId).toList());
    }

    @Test
    void shouldNeverCallTheGraphRouteForABaseThatDidNotEnableIt() {
        givenDualRoute();
        when(chunkMapper.selectList(any())).thenReturn(List.of(
                chunk("ck_1", "first chunk", null), chunk("ck_2", "second chunk", null)));

        SearchOutcome outcome = retrievalService.search(KB_ID, command().build());

        verify(graphRetrievalService, never()).recall(anyString(), any(), anyInt());
        assertTrue(outcome.getDegraded().isEmpty());
    }

    @Test
    void shouldCutTheDisabledChildOutOfTheParentTextAndCountIt() {
        givenTwoLevelBase(false);
        givenParentChildRecall(disabledChild("ck_off", 2, 5));

        SearchOutcome outcome = retrievalService.search(KB_ID, command().build());

        RetrievalNodeView node = outcome.getNodes().get(0);
        assertEquals("甲乙" + ParentTextRedactor.REDACTION_MARK + "己庚辛", node.getContent());
        assertEquals(1, node.getMetadata().get("redacted_child_count"));
        // The ids stay next to the count: a caller has to know which child was excluded, not only how many.
        assertEquals(List.of("ck_off"), node.getMetadata().get("disabled_child_ids"));
    }

    @Test
    void shouldReturnTheWholeParentWhenADisabledChildLostItsOffset() {
        givenTwoLevelBase(false);
        givenParentChildRecall(disabledChild("ck_off", null, null));

        SearchOutcome outcome = retrievalService.search(KB_ID, command().build());

        RetrievalNodeView node = outcome.getNodes().get(0);
        // A half redacted parent reads as complete while missing a section, so an unknown offset falls back
        // to the pre M9 behaviour: the whole parent plus the disabled child marker.
        assertEquals(PARENT_TEXT, node.getContent());
        assertFalse(node.getMetadata().containsKey("redacted_child_count"));
        assertEquals(List.of("ck_off"), node.getMetadata().get("disabled_child_ids"));
    }

    @Test
    void shouldReportNoRedactionCountWhenNoChildIsDisabled() {
        givenTwoLevelBase(false);
        givenParentChildRecall();

        RetrievalNodeView node = retrievalService.search(KB_ID, command().build()).getNodes().get(0);

        assertEquals(PARENT_TEXT, node.getContent());
        assertFalse(node.getMetadata().containsKey("redacted_child_count"));
        assertFalse(node.getMetadata().containsKey("disabled_child_ids"));
    }

    @Test
    void shouldKeepHidingTheWholeParentWhenTheStrictSwitchIsOn() {
        givenTwoLevelBase(true);
        givenParentChildRecall(disabledChild("ck_off", 2, 5));

        SearchOutcome outcome = retrievalService.search(KB_ID, command().build());

        // The strict base never redacts: it removes the unit, exactly as it did before this milestone.
        assertTrue(outcome.getNodes().isEmpty());
    }

    /**
     * A two level knowledge base with the parent suppression switch in the requested position.
     *
     * @param hideParentWithDisabledChild value of the knowledge base switch
     */
    private void givenTwoLevelBase(boolean hideParentWithDisabledChild) {
        KbIndexConfig config = indexConfig(true);
        config.setHideParentWithDisabledChild(hideParentWithDisabledChild);
        KnowledgeBase knowledgeBase = knowledgeBase(true);
        knowledgeBase.setIndexConfig(JsonUtil.toJson(config));
        when(knowledgeBaseService.require(KB_ID)).thenReturn(knowledgeBase);
        when(knowledgeBaseService.indexConfigOf(any(KnowledgeBase.class))).thenReturn(config);
    }

    /**
     * One recalled child under a parent, with the disabled siblings the fact source holds.
     *
     * <p>The three consecutive answers mirror the three reads the pipeline performs: the fact source rows
     * of the candidates, the disabled children of the returned parents, and the parent rows themselves.
     *
     * @param disabled disabled children of the parent, possibly none
     */
    private void givenParentChildRecall(Chunk... disabled) {
        when(embeddingProvider.isConfigured()).thenReturn(false);
        when(fulltextStore.searchBm25(anyString(), any()))
                .thenReturn(List.of(new ScoredChunk("ck_hit", 9.0d, RetrievalSource.BM25)));
        Chunk parent = chunk(PARENT_ID, PARENT_TEXT, null);
        when(chunkMapper.selectList(any()))
                .thenReturn(List.of(chunk("ck_hit", "丁戊", PARENT_ID)))
                .thenReturn(List.of(disabled))
                .thenReturn(List.of(parent));
    }

    /**
     * A disabled child row of the parent under test.
     *
     * @param chunkId child chunk business id
     * @param start   start offset inside the parent text, {@code null} when it was invalidated
     * @param end     exclusive end offset inside the parent text
     * @return disabled child row
     */
    private Chunk disabledChild(String chunkId, Integer start, Integer end) {
        Chunk child = chunk(chunkId, "丙丁戊", PARENT_ID);
        child.setEnabled(0);
        child.setParentStartOffset(start);
        child.setParentEndOffset(end);
        return child;
    }

    /**
     * Makes the knowledge base ask for the graph route.
     */
    private void givenGraphEnabledBase() {
        KnowledgeBase knowledgeBase = knowledgeBase(false);
        KbRetrievalConfig retrievalConfig = new KbRetrievalConfig();
        retrievalConfig.setGraphEnabled(true);
        knowledgeBase.setRetrievalConfig(JsonUtil.toJson(retrievalConfig));
        when(knowledgeBaseService.require(KB_ID)).thenReturn(knowledgeBase);
    }

    private void givenGraphRoute(List<ScoredChunk> candidates,
                                 Map<String, GraphChunkRelevance> evidence) {
        when(graphRetrievalService.recall(anyString(), any(), anyInt()))
                .thenReturn(GraphRouteOutcome.of(candidates, evidence));
    }

    /**
     * Both stores are asked in lite mode because both routes name the same physical index.
     */
    private void givenSnapshotIndexPresent() {
        when(fulltextStore.indexExists(SNAPSHOT_INDEX)).thenReturn(true);
        when(vectorStore.indexExists(SNAPSHOT_INDEX)).thenReturn(true);
    }

    private RetrievalCommand.RetrievalCommandBuilder snapshotCommand() {
        return command()
                .indexOverride(Map.of(KB_ID, new RetrievalIndexOverride(SNAPSHOT_INDEX, SNAPSHOT_INDEX)))
                .visibleVersionIdsOverride(Map.of(KB_ID, List.of(FROZEN_VERSION_ID)));
    }

    private void givenTwoBases() {
        when(knowledgeBaseService.require(KB_ID_2)).thenReturn(knowledgeBaseOf(KB_ID_2));
        when(indexAliasManager.fulltextAlias(KB_ID_2)).thenReturn(FULLTEXT_ALIAS_2);
        when(indexAliasManager.vectorAlias(KB_ID_2)).thenReturn(VECTOR_ALIAS_2);
    }

    private List<KbRef> twoRefs(int weightA, int weightB) {
        return List.of(new KbRef(KB_ID, weightA), new KbRef(KB_ID_2, weightB));
    }

    private KnowledgeBase knowledgeBaseOf(String kbId) {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setKbId(kbId);
        knowledgeBase.setName(kbId);
        knowledgeBase.setIndexConfig(JsonUtil.toJson(indexConfig(false)));
        return knowledgeBase;
    }

    private Chunk chunk(String chunkId, String content, String parentId, String kbId) {
        Chunk chunk = chunk(chunkId, content, parentId);
        chunk.setKbId(kbId);
        return chunk;
    }

    private void givenDualRoute() {
        when(embeddingProvider.isConfigured()).thenReturn(true);
        when(embeddingProvider.embed(any())).thenReturn(List.of(new float[]{0.1f, 0.2f}));
        when(fulltextStore.searchBm25(anyString(), any())).thenReturn(List.of(
                new ScoredChunk("ck_2", 9.0d, RetrievalSource.BM25),
                new ScoredChunk("ck_1", 4.0d, RetrievalSource.BM25)));
        when(vectorStore.search(anyString(), any())).thenReturn(List.of(
                new ScoredChunk("ck_1", 0.91d, RetrievalSource.VECTOR)));
    }

    private RetrievalCommand.RetrievalCommandBuilder command() {
        return RetrievalCommand.builder().query("knowledge").messages(List.of());
    }

    /**
     * Base64 of a small arbitrary payload; the bytes never reach a real provider in these tests.
     *
     * @return base64 image payload
     */
    private String image() {
        return java.util.Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4});
    }

    private KnowledgeBase knowledgeBase(boolean parentChild) {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setKbId(KB_ID);
        knowledgeBase.setName("test");
        knowledgeBase.setIndexConfig(JsonUtil.toJson(indexConfig(parentChild)));
        return knowledgeBase;
    }

    private KbIndexConfig indexConfig(boolean parentChild) {
        KbIndexConfig config = new KbIndexConfig();
        config.setSplitStrategy("fixed_length");
        config.setChunkMaxTokens(600);
        config.setChunkOverlap(100);
        ParentChildParams params = new ParentChildParams();
        params.setEnabled(parentChild);
        config.setParentChild(params);
        return config;
    }

    private Document document() {
        Document document = new Document();
        document.setDocId("doc_test");
        document.setKbId(KB_ID);
        document.setCurrentVersionId(VERSION_ID);
        return document;
    }

    /**
     * An indexed chat aggregation window, carrying the conversation and the message range the near
     * duplicate merging keys on.
     *
     * @param chunkId   chunk business id
     * @param spanStart first message index of the window, inclusive
     * @param spanEnd   last message index of the window, inclusive
     * @return chat log chunk
     */
    private Chunk chatWindow(String chunkId, int spanStart, int spanEnd) {
        Chunk chunk = chunk(chunkId, "chat window " + chunkId, null);
        chunk.setChunkType(ChunkType.CHAT_LOG);
        chunk.setMetadata("{\"session_id\":\"session_1\",\"window_seq\":0,\"msg_span\":["
                + spanStart + "," + spanEnd + "]}");
        return chunk;
    }

    private Chunk chunk(String chunkId, String content, String parentId) {
        Chunk chunk = new Chunk();
        chunk.setChunkId(chunkId);
        chunk.setKbId(KB_ID);
        chunk.setDocId("doc_test");
        chunk.setDocumentVersionId(VERSION_ID);
        chunk.setContent(content);
        chunk.setParentId(parentId);
        chunk.setSeq(0);
        chunk.setChunkType(ChunkType.TEXT);
        chunk.setEnabled(1);
        chunk.setEmbeddingStatus(EmbeddingStatus.SKIPPED);
        return chunk;
    }
}
