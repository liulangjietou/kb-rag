package io.kbrag.app.index;

import io.kbrag.app.support.MybatisLambdaCache;
import io.kbrag.domain.entity.IndexRegistry;
import io.kbrag.domain.enums.IndexRegistryStatus;
import io.kbrag.domain.enums.VectorEngine;
import io.kbrag.domain.mapper.IndexRegistryMapper;
import io.kbrag.domain.model.AppIndexSnapshot;
import io.kbrag.domain.port.FulltextStore;
import io.kbrag.domain.port.VectorStore;
import io.kbrag.domain.service.IndexNaming;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the snapshot primitive of requirement section 4.7: the knowledge base level sequence, the registry row
 * a snapshot leaves behind, the deliberate absence of an alias binding, and the quality stop broadcast that is
 * the one operation allowed to reach into a snapshot.
 *
 * @author owlzhangfq@gmail.com
 */
class IndexSnapshotServiceTest {

    private static final String KB_ID = "kb_alpha";
    private static final String LIVE_ES_INDEX = "kb_alpha_none_v1";
    private static final String LIVE_BM25_INDEX = "kb_alpha_bm25_v1";
    private static final String LIVE_MILVUS_INDEX = "kb_alpha_tev4_v1";
    private static final String ES_ALIAS = "kb_alpha_es";
    private static final String MILVUS_ALIAS = "kb_alpha_milvus";

    private IndexAliasManager indexAliasManager;
    private FulltextStore fulltextStore;
    private VectorStore vectorStore;
    private IndexRegistryMapper indexRegistryMapper;
    private IndexSnapshotService service;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.register(IndexRegistry.class);
        indexAliasManager = mock(IndexAliasManager.class);
        fulltextStore = mock(FulltextStore.class);
        vectorStore = mock(VectorStore.class);
        indexRegistryMapper = mock(IndexRegistryMapper.class);
        service = new IndexSnapshotService(indexAliasManager, new IndexNaming(), fulltextStore, vectorStore,
                indexRegistryMapper);
    }

    @Test
    void shouldNameTheFirstSnapshotS1AndTheNextOneS2() {
        givenLiteMode();
        when(indexRegistryMapper.selectList(any())).thenReturn(List.of(liveRow()));

        List<AppIndexSnapshot> first = service.snapshot(KB_ID);

        assertEquals(1, first.size());
        assertEquals("kb_alpha_none_s1", first.get(0).physicalIndexName());
        verify(fulltextStore).snapshotIndex(LIVE_ES_INDEX, "kb_alpha_none_s1");

        // The next sequence is derived from the registry, so a second release of the same base takes s2.
        when(indexRegistryMapper.selectList(any()))
                .thenReturn(List.of(liveRow(), snapshotRow("kb_alpha_none_s1", "s1", VectorEngine.ES)));

        List<AppIndexSnapshot> second = service.snapshot(KB_ID);

        assertEquals("kb_alpha_none_s2", second.get(0).physicalIndexName());
    }

    @Test
    void shouldShareOneSequenceBetweenBothEnginesAndKeepTheBm25Segment() {
        givenFullMode();
        when(indexRegistryMapper.selectList(any())).thenReturn(List.of());

        List<AppIndexSnapshot> snapshots = service.snapshot(KB_ID);

        assertEquals(2, snapshots.size());
        // One release, one sequence: the BM25 snapshot keeps the bm25 segment and the collection keeps its
        // embedding segment, so both names carry the same s1 and can be matched to the release by eye.
        assertEquals("kb_alpha_bm25_s1", snapshots.get(0).physicalIndexName());
        assertEquals(VectorEngine.ES.code(), snapshots.get(0).engine());
        assertEquals("kb_alpha_tev4_s1", snapshots.get(1).physicalIndexName());
        assertEquals(VectorEngine.MILVUS.code(), snapshots.get(1).engine());
        verify(fulltextStore).snapshotIndex(LIVE_BM25_INDEX, "kb_alpha_bm25_s1");
        verify(vectorStore).snapshotIndex(LIVE_MILVUS_INDEX, "kb_alpha_tev4_s1");
    }

    @Test
    void shouldRegisterASnapshotWithoutBindingItToTheAlias() {
        givenLiteMode();
        when(indexRegistryMapper.selectList(any())).thenReturn(List.of(liveRow()));

        service.snapshot(KB_ID);

        ArgumentCaptor<IndexRegistry> inserted = ArgumentCaptor.forClass(IndexRegistry.class);
        verify(indexRegistryMapper).insert(inserted.capture());
        IndexRegistry row = inserted.getValue();
        assertEquals("kb_alpha_none_s1", row.getPhysicalIndexName());
        assertEquals(IndexRegistryStatus.ACTIVE, row.getStatus());
        assertEquals("s1", row.getSnapshotVersion());
        // The alias name is documentation of the family the snapshot descends from; is_current says the alias
        // does not point here, which is the whole reason the live index keeps serving.
        assertEquals(ES_ALIAS, row.getAliasName());
        assertEquals(0, row.getIsCurrent());
        // Schema and embedding identity are copied from the source row, not from the current provider.
        assertEquals("1", row.getSchemaVersion());
    }

    @Test
    void shouldStopAtTheFirstFailingEngineAndLeaveTheCleanupToTheCaller() {
        givenFullMode();
        when(indexRegistryMapper.selectList(any())).thenReturn(List.of());
        doThrow(new IllegalStateException("milvus down"))
                .when(vectorStore).snapshotIndex(anyString(), anyString());

        assertThrows(IllegalStateException.class, () -> service.snapshot(KB_ID));

        // The full text snapshot was created and is deliberately not dropped here: the release spans several
        // knowledge bases and undoes all of them together.
        verify(fulltextStore).snapshotIndex(LIVE_BM25_INDEX, "kb_alpha_bm25_s1");
        verify(fulltextStore, never()).dropIndex(anyString());
    }

    @Test
    void shouldDropSnapshotsAndMarkTheirRegistryRowsForCleanup() {
        service.drop(List.of(
                new AppIndexSnapshot(KB_ID, VectorEngine.ES.code(), "kb_alpha_bm25_s1"),
                new AppIndexSnapshot(KB_ID, VectorEngine.MILVUS.code(), "kb_alpha_tev4_s1")));

        verify(fulltextStore).dropIndex("kb_alpha_bm25_s1");
        verify(vectorStore).dropIndex("kb_alpha_tev4_s1");
        verify(indexRegistryMapper, times(2)).update(eq(null), any());
    }

    @Test
    void shouldBroadcastTheDisableFlagToEverySnapshotOfTheBase() {
        when(indexRegistryMapper.selectList(any())).thenReturn(List.of(
                snapshotRow("kb_alpha_bm25_s1", "s1", VectorEngine.ES),
                snapshotRow("kb_alpha_tev4_s1", "s1", VectorEngine.MILVUS),
                snapshotRow("kb_alpha_bm25_s2", "s2", VectorEngine.ES)));

        service.broadcastEnabled(KB_ID, List.of("ck_1"), false);

        // A quality stop has to reach every released version still answering, which means every live snapshot
        // and not only the newest one.
        verify(fulltextStore).updateEnabled("kb_alpha_bm25_s1", List.of("ck_1"), false);
        verify(fulltextStore).updateEnabled("kb_alpha_bm25_s2", List.of("ck_1"), false);
        verify(vectorStore).updateEnabled("kb_alpha_tev4_s1", List.of("ck_1"), false);
    }

    @Test
    void shouldKeepBroadcastingWhenOneSnapshotRefusesTheUpdate() {
        when(indexRegistryMapper.selectList(any())).thenReturn(List.of(
                snapshotRow("kb_alpha_bm25_s1", "s1", VectorEngine.ES),
                snapshotRow("kb_alpha_bm25_s2", "s2", VectorEngine.ES)));
        doThrow(new IllegalStateException("index closed"))
                .when(fulltextStore).updateEnabled(eq("kb_alpha_bm25_s1"), anyList(), anyBoolean());

        service.broadcastEnabled(KB_ID, List.of("ck_1"), false);

        // The live index is already updated and MySQL already says disabled, so a snapshot that cannot take the
        // flag must not stop the remaining ones from taking it.
        verify(fulltextStore).updateEnabled("kb_alpha_bm25_s2", List.of("ck_1"), false);
    }

    @Test
    void shouldNotTouchAnyEngineWhenThereIsNothingToBroadcast() {
        service.broadcastEnabled(KB_ID, List.of(), false);

        verify(fulltextStore, never()).updateEnabled(anyString(), anyList(), anyBoolean());
        verify(indexRegistryMapper, never()).selectList(any());
    }

    private void givenLiteMode() {
        when(indexAliasManager.resolveTargets(KB_ID)).thenReturn(List.of(
                new IndexTarget(VectorEngine.ES, LIVE_ES_INDEX, ES_ALIAS, "none", false, 0)));
        when(indexRegistryMapper.selectOne(any())).thenReturn(null, liveRow());
    }

    private void givenFullMode() {
        when(indexAliasManager.resolveTargets(KB_ID)).thenReturn(List.of(
                new IndexTarget(VectorEngine.ES, LIVE_BM25_INDEX, ES_ALIAS, "bm25", false, 0),
                new IndexTarget(VectorEngine.MILVUS, LIVE_MILVUS_INDEX, MILVUS_ALIAS, "tev4", true, 1024)));
        when(indexRegistryMapper.selectOne(any())).thenReturn(null);
    }

    private IndexRegistry liveRow() {
        IndexRegistry row = new IndexRegistry();
        row.setKbId(KB_ID);
        row.setEngine(VectorEngine.ES.code());
        row.setPhysicalIndexName(LIVE_ES_INDEX);
        row.setAliasName(ES_ALIAS);
        row.setIsCurrent(1);
        row.setSnapshotVersion("v1");
        row.setSchemaVersion("1");
        row.setStatus(IndexRegistryStatus.ACTIVE);
        return row;
    }

    private IndexRegistry snapshotRow(String physicalIndexName, String segment, VectorEngine engine) {
        IndexRegistry row = new IndexRegistry();
        row.setKbId(KB_ID);
        row.setEngine(engine.code());
        row.setPhysicalIndexName(physicalIndexName);
        row.setAliasName(engine == VectorEngine.ES ? ES_ALIAS : MILVUS_ALIAS);
        row.setIsCurrent(0);
        row.setSnapshotVersion(segment);
        row.setSchemaVersion("1");
        row.setStatus(IndexRegistryStatus.ACTIVE);
        return row;
    }
}
