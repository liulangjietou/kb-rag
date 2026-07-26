package io.kbrag.app.graph;

import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.enums.DegradedReason;
import io.kbrag.domain.enums.RetrievalSource;
import io.kbrag.domain.mapper.ChunkMapper;
import io.kbrag.domain.model.GraphTraceRow;
import io.kbrag.domain.model.GraphTraversalQuery;
import io.kbrag.domain.model.RetrievalFilter;
import io.kbrag.domain.model.ScoredChunk;
import io.kbrag.domain.port.GraphStore;
import io.kbrag.domain.service.GraphQueryTokenizer;
import io.kbrag.domain.service.GraphRelevanceScorer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the graph route on its own: the tokenised match, the fact source re-check of what the graph
 * proposed, and the degradation of an unreachable or unconfigured graph.
 *
 * @author owlzhangfq@gmail.com
 */
class GraphRetrievalServiceTest {

    private static final String KB_ID = "kb_test";
    private static final String VERSION_ID = "dv_active";
    private static final int RECALL_TOP_K = 10;
    private static final double DELTA = 1e-9d;

    private GraphStore graphStore;
    private ChunkMapper chunkMapper;
    private KbProperties properties;
    private GraphRetrievalService service;

    @BeforeEach
    void setUp() {
        graphStore = mock(GraphStore.class);
        chunkMapper = mock(ChunkMapper.class);
        properties = new KbProperties();
        when(graphStore.isEnabled()).thenReturn(true);
        service = new GraphRetrievalService(graphStore, chunkMapper, new GraphQueryTokenizer(),
                new GraphRelevanceScorer(), properties);
    }

    @Test
    void shouldRecallTheChunkAMultiHopPathReaches() {
        when(graphStore.traverse(any())).thenReturn(List.of(
                new GraphTraceRow("ck_1", "苹果公司", 0.8d, 1)));
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk("ck_1", 1)));

        GraphRouteOutcome outcome = service.recall("苹果公司的创始人", filter(), RECALL_TOP_K);

        assertNull(outcome.getDegradedReason());
        assertEquals(1, outcome.getCandidates().size());
        ScoredChunk candidate = outcome.getCandidates().get(0);
        assertEquals("ck_1", candidate.getChunkId());
        assertEquals(RetrievalSource.GRAPH, candidate.getSource());
        assertEquals(0.4d, candidate.getScore(), DELTA);
        assertEquals(1, outcome.getEvidenceByChunk().get("ck_1").hops());
        assertEquals(List.of("苹果公司"), outcome.getEvidenceByChunk().get("ck_1").entityNames());
    }

    @Test
    void shouldPassTheTokenisedChineseQueryAndTheConfiguredLimitsToTheStore() {
        properties.getGraph().setMaxHops(3);
        properties.getGraph().setEntityMatchLimit(7);
        when(graphStore.traverse(any())).thenReturn(List.of());

        service.recall("苹果公司的创始人，是谁？", filter(), RECALL_TOP_K);

        ArgumentCaptor<GraphTraversalQuery> query = ArgumentCaptor.forClass(GraphTraversalQuery.class);
        verify(graphStore).traverse(query.capture());
        assertEquals(List.of("苹果公司的创始人", "是谁"), query.getValue().getTerms());
        assertEquals(KB_ID, query.getValue().getKbId());
        assertEquals(3, query.getValue().getMaxHops());
        assertEquals(7, query.getValue().getEntityMatchLimit());
        assertEquals(RECALL_TOP_K, query.getValue().getChunkLimit());
    }

    @Test
    void shouldDropAChunkTheFactSourceNoLongerAdmits() {
        when(graphStore.traverse(any())).thenReturn(List.of(
                new GraphTraceRow("ck_visible", "seed", 1.0d, 0),
                new GraphTraceRow("ck_disabled", "seed", 0.9d, 0)));
        // The MySQL predicate carries "enabled" and the visible version set, so a disabled chunk simply
        // does not come back - the graph never learns it was disabled, which is why the re-check exists.
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk("ck_visible", 1)));

        GraphRouteOutcome outcome = service.recall("seed", filter(), RECALL_TOP_K);

        assertEquals(List.of("ck_visible"),
                outcome.getCandidates().stream().map(ScoredChunk::getChunkId).toList());
        assertTrue(outcome.getEvidenceByChunk().containsKey("ck_visible"));
        assertTrue(outcome.getEvidenceByChunk().isEmpty() || !outcome.getEvidenceByChunk()
                .containsKey("ck_disabled"));
    }

    @Test
    void shouldReportTheRouteAsUnavailableWhenNoGraphIsConfigured() {
        when(graphStore.isEnabled()).thenReturn(false);

        GraphRouteOutcome outcome = service.recall("seed", filter(), RECALL_TOP_K);

        assertEquals(DegradedReason.GRAPH_ROUTE_UNAVAILABLE.code(), outcome.getDegradedReason());
        assertTrue(outcome.getCandidates().isEmpty());
        verify(graphStore, never()).traverse(any());
    }

    @Test
    void shouldReportTheRouteAsUnavailableWhenTheGraphCannotBeReached() {
        when(graphStore.traverse(any())).thenThrow(new IllegalStateException("connection refused"));

        GraphRouteOutcome outcome = service.recall("seed", filter(), RECALL_TOP_K);

        assertEquals(DegradedReason.GRAPH_ROUTE_UNAVAILABLE.code(), outcome.getDegradedReason());
        assertTrue(outcome.getCandidates().isEmpty());
    }

    @Test
    void shouldSkipSilentlyWhenTheQueryCarriesNoSearchableTerm() {
        GraphRouteOutcome outcome = service.recall("???", filter(), RECALL_TOP_K);

        assertNull(outcome.getDegradedReason());
        assertTrue(outcome.getCandidates().isEmpty());
        verify(graphStore, never()).traverse(any());
    }

    @Test
    void shouldSkipSilentlyWhenTheGraphMatchedNothing() {
        when(graphStore.traverse(any())).thenReturn(List.of());

        GraphRouteOutcome outcome = service.recall("seed", filter(), RECALL_TOP_K);

        assertNull(outcome.getDegradedReason());
        assertTrue(outcome.getCandidates().isEmpty());
    }

    private RetrievalFilter filter() {
        return RetrievalFilter.builder()
                .kbId(KB_ID)
                .documentVersionIds(List.of(VERSION_ID))
                .enabledOnly(true)
                .build();
    }

    private Chunk chunk(String chunkId, int enabled) {
        Chunk chunk = new Chunk();
        chunk.setChunkId(chunkId);
        chunk.setKbId(KB_ID);
        chunk.setDocumentVersionId(VERSION_ID);
        chunk.setEnabled(enabled);
        return chunk;
    }
}
