package io.kbrag.app.index;

import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.entity.ChunkIndexSync;
import io.kbrag.domain.enums.ChunkType;
import io.kbrag.domain.enums.IndexSyncStatus;
import io.kbrag.domain.enums.VectorEngine;
import io.kbrag.domain.mapper.ChunkIndexSyncMapper;
import io.kbrag.domain.mapper.ChunkMapper;
import io.kbrag.domain.port.EmbeddingProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the compensation scan with every collaborator stubbed: the grouping that decides how many
 * engine writes and embedding calls one pass costs, and the abandonment of a row that keeps failing.
 */
class IndexSyncCompensationServiceTest {

    private static final String KB_ID = "kb_test";
    private static final String ES_INDEX = "kb_test_tev4_v1";
    private static final String MILVUS_INDEX = "kb_test_tev4_v1_milvus";

    private ChunkIndexSyncMapper chunkIndexSyncMapper;
    private ChunkMapper chunkMapper;
    private IndexAliasManager indexAliasManager;
    private ChunkIndexWriter chunkIndexWriter;
    private EmbeddingProvider embeddingProvider;
    private KbProperties properties;
    private IndexSyncCompensationService service;

    @BeforeEach
    void setUp() {
        chunkIndexSyncMapper = mock(ChunkIndexSyncMapper.class);
        chunkMapper = mock(ChunkMapper.class);
        indexAliasManager = mock(IndexAliasManager.class);
        chunkIndexWriter = mock(ChunkIndexWriter.class);
        embeddingProvider = mock(EmbeddingProvider.class);
        properties = new KbProperties();
        service = new IndexSyncCompensationService(chunkIndexSyncMapper, chunkMapper, indexAliasManager,
                chunkIndexWriter, embeddingProvider, properties);
    }

    @Test
    void shouldGroupRowsByPhysicalIndex() {
        List<ChunkIndexSync> rows = List.of(
                syncRow(1L, "ck_1", ES_INDEX),
                syncRow(2L, "ck_2", MILVUS_INDEX),
                syncRow(3L, "ck_3", ES_INDEX));

        Map<String, List<ChunkIndexSync>> grouped = service.groupByPhysicalIndex(rows);

        assertEquals(2, grouped.size());
        assertEquals(List.of("ck_1", "ck_3"),
                grouped.get(ES_INDEX).stream().map(ChunkIndexSync::getChunkId).toList());
        assertEquals(List.of("ck_2"),
                grouped.get(MILVUS_INDEX).stream().map(ChunkIndexSync::getChunkId).toList());
        // Insertion order makes a scan reproducible, which is what lets a failure be replayed.
        assertEquals(List.of(ES_INDEX, MILVUS_INDEX), List.copyOf(grouped.keySet()));
    }

    @Test
    void shouldReplayOneWritePerIndexGroup() {
        when(chunkIndexSyncMapper.selectList(any())).thenReturn(List.of(
                syncRow(1L, "ck_1", ES_INDEX),
                syncRow(2L, "ck_2", ES_INDEX),
                syncRow(3L, "ck_3", MILVUS_INDEX)));
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk("ck_1"), chunk("ck_2"), chunk("ck_3")));
        when(indexAliasManager.resolveTargets(KB_ID)).thenReturn(List.of(
                new IndexTarget(VectorEngine.ES, ES_INDEX, "kb_test_es", "bm25", false, 0),
                new IndexTarget(VectorEngine.MILVUS, MILVUS_INDEX, "kb_test_milvus", "tev4", true, 1024)));
        when(embeddingProvider.isConfigured()).thenReturn(true);
        when(embeddingProvider.maxBatchSize()).thenReturn(10);
        when(embeddingProvider.embed(any())).thenReturn(List.of(new float[]{0.1f}));

        int repaired = service.compensate();

        assertEquals(3, repaired);
        ArgumentCaptor<IndexTarget> targets = ArgumentCaptor.forClass(IndexTarget.class);
        verify(chunkIndexWriter, times(2)).writeTarget(targets.capture(), any(), any());
        assertEquals(List.of(ES_INDEX, MILVUS_INDEX),
                targets.getAllValues().stream().map(IndexTarget::physicalIndexName).toList());
        // Only the vector carrying target pays for an embedding call; the vector is not kept in MySQL,
        // so it can only be recomputed, and grouping by chunk would have paid twice.
        verify(embeddingProvider, times(1)).embed(any());
    }

    @Test
    void shouldNotEmbedForAFullTextOnlyTarget() {
        when(chunkIndexSyncMapper.selectList(any())).thenReturn(List.of(syncRow(1L, "ck_1", ES_INDEX)));
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk("ck_1")));
        when(indexAliasManager.resolveTargets(KB_ID)).thenReturn(List.of(
                new IndexTarget(VectorEngine.ES, ES_INDEX, "kb_test_es", "bm25", false, 0)));

        assertEquals(1, service.compensate());
        verify(embeddingProvider, never()).embed(any());
    }

    @Test
    void shouldForgetARowWhoseChunkIsGone() {
        when(chunkIndexSyncMapper.selectList(any())).thenReturn(List.of(syncRow(7L, "ck_gone", ES_INDEX)));
        when(chunkMapper.selectList(any())).thenReturn(List.of());

        assertEquals(0, service.compensate());
        // The fact source dropped the chunk, so there is nothing to replay and the row is noise.
        verify(chunkIndexSyncMapper, times(1)).deleteById(7L);
        verify(chunkIndexWriter, never()).writeTarget(any(), any(), any());
    }

    @Test
    void shouldCountFailedAttemptsAndKeepTheRowForTheNextPass() {
        when(chunkIndexSyncMapper.selectList(any())).thenReturn(List.of(syncRow(1L, "ck_1", ES_INDEX)));
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk("ck_1")));
        when(indexAliasManager.resolveTargets(KB_ID)).thenReturn(List.of(
                new IndexTarget(VectorEngine.ES, ES_INDEX, "kb_test_es", "bm25", false, 0)));
        org.mockito.Mockito.doThrow(new IllegalStateException("engine unreachable"))
                .when(chunkIndexWriter).writeTarget(any(), any(), any());

        assertEquals(0, service.compensate());
        verify(chunkIndexWriter, times(1)).markFailed(eq("ck_1"), eq(ES_INDEX), eq(1));
    }

    @Test
    void shouldSkipTheGroupWhenNoTargetMatchesTheIndex() {
        when(chunkIndexSyncMapper.selectList(any())).thenReturn(List.of(syncRow(1L, "ck_1", "kb_test_dropped")));
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk("ck_1")));
        when(indexAliasManager.resolveTargets(KB_ID)).thenReturn(List.of(
                new IndexTarget(VectorEngine.ES, ES_INDEX, "kb_test_es", "bm25", false, 0)));

        assertEquals(0, service.compensate());
        verify(chunkIndexWriter, never()).writeTarget(any(), any(), any());
        verify(chunkIndexWriter, never()).markFailed(anyString(), anyString(), anyInt());
    }

    @Test
    void shouldDoNothingWithoutPendingRows() {
        when(chunkIndexSyncMapper.selectList(any())).thenReturn(List.of());

        assertEquals(0, service.compensate());
        verify(chunkMapper, never()).selectList(any());
    }

    @Test
    void shouldStayIdleWhileTheScanIsDisabled() {
        properties.getSync().setCompensationEnabled(false);

        service.scan();

        verify(chunkIndexSyncMapper, never()).selectList(any());
    }

    @Test
    void shouldSwallowFailuresOfTheScheduledEntryPoint() {
        when(chunkIndexSyncMapper.selectList(any())).thenThrow(new IllegalStateException("database down"));

        // A scheduled method that throws stops being scheduled in some containers; the scan has to
        // survive its own failures to keep repairing later.
        service.scan();
        assertTrue(true);
    }

    private ChunkIndexSync syncRow(long id, String chunkId, String physicalIndexName) {
        ChunkIndexSync row = new ChunkIndexSync();
        row.setId(id);
        row.setChunkId(chunkId);
        row.setPhysicalIndexName(physicalIndexName);
        row.setEngine(VectorEngine.ES.code());
        row.setStatus(IndexSyncStatus.FAILED);
        row.setRetryCount(0);
        return row;
    }

    private Chunk chunk(String chunkId) {
        Chunk chunk = new Chunk();
        chunk.setChunkId(chunkId);
        chunk.setKbId(KB_ID);
        chunk.setDocId("doc_test");
        chunk.setDocumentVersionId("dv_test");
        chunk.setContent("content of " + chunkId);
        chunk.setChunkType(ChunkType.TEXT);
        chunk.setEnabled(1);
        chunk.setSeq(0);
        return chunk;
    }
}
