package io.kbrag.app.retrieval;

import io.kbrag.app.alert.RetrievalDegradeMonitor;
import io.kbrag.app.index.EngineChunkCleaner;
import io.kbrag.app.index.IndexAliasManager;
import io.kbrag.app.kb.KnowledgeBaseService;
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
import io.kbrag.domain.model.MetadataFilter;
import io.kbrag.domain.model.ParentChildParams;
import io.kbrag.domain.model.RetrievalFilter;
import io.kbrag.domain.model.ScoredChunk;
import io.kbrag.domain.port.EmbeddingProvider;
import io.kbrag.domain.port.FulltextStore;
import io.kbrag.domain.port.ObjectStorage;
import io.kbrag.domain.port.VectorStore;
import io.kbrag.domain.service.FusionRouter;
import io.kbrag.domain.service.RrfFusion;
import io.kbrag.domain.service.WeightedFusion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
    private static final String FULLTEXT_ALIAS = "kb_test_es";
    private static final String VECTOR_ALIAS = "kb_test_es";

    private KnowledgeBaseService knowledgeBaseService;
    private DocumentMapper documentMapper;
    private ChunkMapper chunkMapper;
    private FulltextStore fulltextStore;
    private VectorStore vectorStore;
    private EmbeddingProvider embeddingProvider;
    private IndexAliasManager indexAliasManager;
    private RewriteService rewriteService;
    private RerankService rerankService;
    private EngineChunkCleaner engineChunkCleaner;
    private ObjectStorage objectStorage;
    private KbProperties properties;
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
        rewriteService = mock(RewriteService.class);
        rerankService = mock(RerankService.class);
        engineChunkCleaner = mock(EngineChunkCleaner.class);
        objectStorage = mock(ObjectStorage.class);
        properties = new KbProperties();

        when(knowledgeBaseService.require(KB_ID)).thenReturn(knowledgeBase(false));
        when(knowledgeBaseService.indexConfigOf(any(KnowledgeBase.class))).thenReturn(indexConfig(false));
        when(documentMapper.selectList(any())).thenReturn(List.of(document()));
        when(indexAliasManager.fulltextAlias(KB_ID)).thenReturn(FULLTEXT_ALIAS);
        when(indexAliasManager.vectorAlias(KB_ID)).thenReturn(VECTOR_ALIAS);
        when(rewriteService.isAvailable()).thenReturn(false);
        when(rewriteService.rewrite(anyString(), any(), eq(false)))
                .thenAnswer(invocation -> RewriteOutcome.skipped(invocation.getArgument(0)));
        when(rerankService.isAvailable()).thenReturn(false);
        when(rerankService.candidateLimit()).thenReturn(50);
        when(rerankService.rerank(anyString(), anyList(), eq(false))).thenReturn(RerankOutcome.skipped());

        retrievalService = new RetrievalService(knowledgeBaseService, documentMapper, chunkMapper,
                fulltextStore, vectorStore, embeddingProvider, indexAliasManager,
                new FusionRouter(List.of(new RrfFusion(), new WeightedFusion())),
                rewriteService, rerankService, new ScoreThresholdPolicy(), new ParentChildMerger(),
                engineChunkCleaner, objectStorage, new RetrievalDegradeMonitor(properties), properties);
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
        when(chunkMapper.selectList(any()))
                .thenReturn(List.of(
                        chunk("ck_child_a", "first passage", "ck_parent"),
                        chunk("ck_child_b", "second passage", "ck_parent")))
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
