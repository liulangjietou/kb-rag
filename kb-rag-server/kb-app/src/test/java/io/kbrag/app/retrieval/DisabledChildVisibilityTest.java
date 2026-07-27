package io.kbrag.app.retrieval;

import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.enums.ChunkType;
import io.kbrag.domain.enums.FusionMode;
import io.kbrag.domain.enums.RetrievalSource;
import io.kbrag.domain.mapper.ChunkMapper;
import io.kbrag.domain.model.FusedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Covers what a parent containing a disabled child is allowed to return, under both settings of the
 * knowledge base switch. The two behaviours are opposite - report or suppress - so a mix-up either
 * leaks excluded text or silently drops a whole section from every answer.
 *
 * @author owlzhangfq@gmail.com
 */
class DisabledChildVisibilityTest {

    private static final String PARENT_ID = "ck_parent";
    private static final String OTHER_PARENT_ID = "ck_parent_other";

    private final DisabledChildVisibility visibility =
            new DisabledChildVisibility(mock(ChunkMapper.class));
    private final ParentChildMerger merger = new ParentChildMerger();

    @Test
    void shouldReportTheDisabledChildrenWhileStillReturningTheParent() {
        List<RetrievalUnit> units = merger.merge(List.of(candidate("ck_child_a", PARENT_ID)));

        DisabledChildVisibility.Visibility result = visibility.apply(units,
                Map.of(PARENT_ID, List.of(disabledChild("ck_child_b"))), false);

        assertEquals(1, result.units().size());
        assertEquals(PARENT_ID, result.units().get(0).getUnitId());
        assertEquals(List.of("ck_child_b"), result.disabledChildrenByUnit().get(PARENT_ID).stream()
                .map(DisabledChildVisibility.DisabledChild::chunkId).toList());
    }

    @Test
    void shouldSuppressTheParentWhenTheStrictSwitchIsOn() {
        List<RetrievalUnit> units = merger.merge(List.of(candidate("ck_child_a", PARENT_ID)));

        DisabledChildVisibility.Visibility result = visibility.apply(units,
                Map.of(PARENT_ID, List.of(disabledChild("ck_child_b"))), true);

        assertTrue(result.units().isEmpty());
        assertTrue(result.disabledChildrenByUnit().isEmpty());
    }

    @Test
    void shouldOnlySuppressTheParentsThatActuallyContainADisabledChild() {
        List<RetrievalUnit> units = merger.merge(List.of(
                candidate("ck_child_a", PARENT_ID),
                candidate("ck_child_c", OTHER_PARENT_ID)));

        DisabledChildVisibility.Visibility result = visibility.apply(units,
                Map.of(PARENT_ID, List.of(disabledChild("ck_child_b"))), true);

        assertEquals(List.of(OTHER_PARENT_ID),
                result.units().stream().map(RetrievalUnit::getUnitId).toList());
    }

    @Test
    void shouldLeaveASingleLevelResultAlone() {
        // Without two level splitting a chunk is its own unit and has no children to report.
        List<RetrievalUnit> units = merger.merge(List.of(candidate("ck_flat", null)));

        DisabledChildVisibility.Visibility result = visibility.apply(units,
                Map.of("ck_flat", List.of(disabledChild("ck_child_b"))), true);

        assertEquals(List.of("ck_flat"), result.units().stream().map(RetrievalUnit::getUnitId).toList());
        assertTrue(result.disabledChildrenByUnit().isEmpty());
    }

    @Test
    void shouldReportNothingWhenNoChildIsDisabled() {
        List<RetrievalUnit> units = merger.merge(List.of(candidate("ck_child_a", PARENT_ID)));

        DisabledChildVisibility.Visibility result = visibility.apply(units, Map.of(), true);

        assertEquals(1, result.units().size());
        assertTrue(result.disabledChildrenByUnit().isEmpty());
        assertFalse(result.units().isEmpty());
    }

    private DisabledChildVisibility.DisabledChild disabledChild(String chunkId) {
        // No offsets: this suite is about which parents survive the switch, which the M9 precise redaction
        // does not change. The redaction itself is covered by ParentTextRedactorTest and RetrievalServiceTest.
        return new DisabledChildVisibility.DisabledChild(chunkId, null, null);
    }

    private RetrievalCandidate candidate(String chunkId, String parentId) {
        FusedChunk fused = FusedChunk.builder()
                .chunkId(chunkId)
                .fusedScore(1.0d)
                .primarySource(RetrievalSource.BM25)
                .fusionMode(FusionMode.RRF)
                .build();
        return new RetrievalCandidate(fused, chunk(chunkId, parentId));
    }

    private Chunk chunk(String chunkId, String parentId) {
        Chunk chunk = new Chunk();
        chunk.setChunkId(chunkId);
        chunk.setKbId("kb_1");
        chunk.setDocId("doc_1");
        chunk.setDocumentVersionId("dv_1");
        chunk.setContent("passage of " + chunkId);
        chunk.setParentId(parentId);
        chunk.setSeq(0);
        chunk.setChunkType(ChunkType.TEXT);
        chunk.setEnabled(1);
        return chunk;
    }
}
