package io.kbrag.app.retrieval;

import io.kbrag.app.index.IndexAliasManager;
import io.kbrag.app.kb.KnowledgeBaseService;
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
import io.kbrag.domain.model.ScoredChunk;
import io.kbrag.domain.port.EmbeddingProvider;
import io.kbrag.domain.port.FulltextStore;
import io.kbrag.domain.port.VectorStore;
import io.kbrag.domain.service.RrfFusion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the two retrieval shapes without touching a search engine: the dual route path and the zero
 * key single route path with its degradation marker.
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

        when(knowledgeBaseService.require(KB_ID)).thenReturn(knowledgeBase());
        when(documentMapper.selectList(any())).thenReturn(List.of(document()));
        when(indexAliasManager.fulltextAlias(KB_ID)).thenReturn(FULLTEXT_ALIAS);
        when(indexAliasManager.vectorAlias(KB_ID)).thenReturn(VECTOR_ALIAS);

        retrievalService = new RetrievalService(knowledgeBaseService, documentMapper, chunkMapper,
                fulltextStore, vectorStore, embeddingProvider, indexAliasManager, new RrfFusion(),
                new KbProperties());
    }

    @Test
    void shouldFallBackToBm25WhenNoEmbeddingProviderIsConfigured() {
        when(embeddingProvider.isConfigured()).thenReturn(false);
        when(fulltextStore.searchBm25(anyString(), any()))
                .thenReturn(List.of(new ScoredChunk("ck_1", 7.5d, RetrievalSource.BM25)));
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk("ck_1", "knowledge base chunk")));

        SearchOutcome outcome = retrievalService.search(KB_ID, "knowledge", 50, 5);

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
        when(embeddingProvider.isConfigured()).thenReturn(true);
        when(embeddingProvider.embed(any())).thenReturn(List.of(new float[]{0.1f, 0.2f}));
        when(fulltextStore.searchBm25(anyString(), any())).thenReturn(List.of(
                new ScoredChunk("ck_2", 9.0d, RetrievalSource.BM25),
                new ScoredChunk("ck_1", 4.0d, RetrievalSource.BM25)));
        when(vectorStore.search(anyString(), any())).thenReturn(List.of(
                new ScoredChunk("ck_1", 0.91d, RetrievalSource.VECTOR)));
        when(chunkMapper.selectList(any())).thenReturn(List.of(
                chunk("ck_1", "first chunk"), chunk("ck_2", "second chunk")));

        SearchOutcome outcome = retrievalService.search(KB_ID, "knowledge", 50, 5);

        assertTrue(outcome.getDegraded().isEmpty());
        assertEquals(2, outcome.getNodes().size());
        // Recalled by both routes, so it wins the fusion and reports the vector score.
        RetrievalNodeView top = outcome.getNodes().get(0);
        assertEquals("ck_1", top.getChunkId());
        assertEquals(ScoreType.COSINE.code(), top.getScoreType());
        assertEquals(RetrievalSource.VECTOR.code(), top.getRetrievalSource());
        assertEquals(0.91d, top.getScore());
        assertTrue(top.getMetadata().containsKey("rrf_score"));
        assertTrue(top.getMetadata().containsKey("bm25_score"));
    }

    @Test
    void shouldReturnNothingWhenTheKnowledgeBaseHasNoActiveVersion() {
        when(documentMapper.selectList(any())).thenReturn(List.of());

        SearchOutcome outcome = retrievalService.search(KB_ID, "knowledge", 50, 5);

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
                chunk("ck_1", "one"), chunk("ck_2", "two"), chunk("ck_3", "three")));

        SearchOutcome outcome = retrievalService.search(KB_ID, "knowledge", 50, 2);

        assertEquals(2, outcome.getNodes().size());
        assertEquals(List.of("ck_1", "ck_2"),
                outcome.getNodes().stream().map(RetrievalNodeView::getChunkId).toList());
    }

    private KnowledgeBase knowledgeBase() {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setKbId(KB_ID);
        knowledgeBase.setName("test");
        return knowledgeBase;
    }

    private Document document() {
        Document document = new Document();
        document.setDocId("doc_test");
        document.setKbId(KB_ID);
        document.setCurrentVersionId(VERSION_ID);
        return document;
    }

    private Chunk chunk(String chunkId, String content) {
        Chunk chunk = new Chunk();
        chunk.setChunkId(chunkId);
        chunk.setKbId(KB_ID);
        chunk.setDocId("doc_test");
        chunk.setDocumentVersionId(VERSION_ID);
        chunk.setContent(content);
        chunk.setSeq(0);
        chunk.setChunkType(ChunkType.TEXT);
        chunk.setEnabled(1);
        chunk.setEmbeddingStatus(EmbeddingStatus.SKIPPED);
        return chunk;
    }
}
