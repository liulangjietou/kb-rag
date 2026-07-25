package io.kbrag.app.retrieval;

import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.enums.ChunkType;
import io.kbrag.domain.enums.FusionMode;
import io.kbrag.domain.enums.RetrievalSource;
import io.kbrag.domain.model.FusedChunk;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the candidate budget and the merge of the two level pipeline, the two places where the number
 * of returned units stops matching the number of recalled candidates.
 */
class ParentChildMergerTest {

    private static final double TOLERANCE = 1e-9;

    private final ParentChildMerger merger = new ParentChildMerger();

    @Test
    void shouldStopOnceEnoughDistinctParentsAreRepresented() {
        // Children alternate between four parents, so the fourth candidate is the first one at which
        // four distinct parents exist.
        List<FusedChunk> fused = fusedList(20);
        Map<String, String> parents = new HashMap<>();
        for (int i = 0; i < 20; i++) {
            parents.put("ck_" + i, "parent_" + (i % 4));
        }

        assertEquals(4, merger.candidateCount(fused, parents, 4, 50));
    }

    @Test
    void shouldKeepTakingChildrenWhileTheyShareParents() {
        // Every child of the first six belongs to one parent, so the budget cannot stop early.
        List<FusedChunk> fused = fusedList(10);
        Map<String, String> parents = new HashMap<>();
        for (int i = 0; i < 10; i++) {
            parents.put("ck_" + i, i < 6 ? "parent_0" : "parent_" + i);
        }

        // Six clustered children plus two more to reach three distinct parents.
        assertEquals(8, merger.candidateCount(fused, parents, 3, 50));
    }

    @Test
    void shouldNeverExceedTheRerankCap() {
        List<FusedChunk> fused = fusedList(120);
        Map<String, String> parents = new HashMap<>();
        for (int i = 0; i < 120; i++) {
            // Every child has its own parent, so only the cap can stop the walk.
            parents.put("ck_" + i, "parent_" + i);
        }

        assertEquals(50, merger.candidateCount(fused, parents, 1000, 50));
    }

    @Test
    void shouldTreatAChildWithoutAParentAsItsOwnUnit() {
        List<FusedChunk> fused = fusedList(5);

        assertEquals(3, merger.candidateCount(fused, Map.of(), 3, 50));
    }

    @Test
    void shouldReturnZeroForAnEmptyCandidateList() {
        assertEquals(0, merger.candidateCount(List.of(), Map.of(), 20, 50));
    }

    @Test
    void shouldDeriveTheParentTargetFromTopN() {
        assertEquals(30, merger.parentTarget(10, 3, 20));
        // The floor protects a small top_n from producing a target the merge cannot work with.
        assertEquals(20, merger.parentTarget(2, 3, 20));
    }

    @Test
    void shouldScoreAParentByItsBestChild() {
        List<RetrievalCandidate> ranked = List.of(
                candidate("ck_a", "parent_1", 0.9d),
                candidate("ck_b", "parent_2", 0.8d),
                candidate("ck_c", "parent_1", 0.4d));

        List<RetrievalUnit> units = merger.merge(ranked);

        assertEquals(2, units.size());
        RetrievalUnit first = units.get(0);
        assertEquals("parent_1", first.getUnitId());
        assertTrue(first.isParent());
        // Maximum, not sum: two mediocre passages must not outrank one good one.
        assertEquals(0.9d, first.score(), TOLERANCE);
        assertEquals(List.of("ck_a", "ck_c"),
                first.getMembers().stream().map(RetrievalCandidate::chunkId).toList());
    }

    @Test
    void shouldOrderUnitsByTheirBestMember() {
        List<RetrievalCandidate> ranked = List.of(
                candidate("ck_a", "parent_1", 0.5d),
                candidate("ck_b", "parent_2", 0.9d),
                candidate("ck_c", "parent_1", 0.4d));

        List<RetrievalUnit> units = merger.merge(ranked);

        assertEquals(List.of("parent_2", "parent_1"),
                units.stream().map(RetrievalUnit::getUnitId).toList());
    }

    @Test
    void shouldWrapASingleLevelChunkInAUnitOfOne() {
        List<RetrievalUnit> units = merger.merge(List.of(candidate("ck_a", null, 0.7d)));

        assertEquals(1, units.size());
        assertEquals("ck_a", units.get(0).getUnitId());
        assertFalse(units.get(0).isParent());
        assertEquals(1, units.get(0).getMembers().size());
    }

    @Test
    void shouldPreferTheRerankScoreWhenOrderingMembers() {
        RetrievalCandidate weakFusion = candidate("ck_a", "parent_1", 0.1d);
        weakFusion.applyRerankScore(0.95d);
        RetrievalCandidate strongFusion = candidate("ck_b", "parent_1", 0.9d);
        strongFusion.applyRerankScore(0.10d);

        List<RetrievalUnit> units = merger.merge(List.of(strongFusion, weakFusion));

        assertEquals("ck_a", units.get(0).best().chunkId());
        assertEquals(0.95d, units.get(0).score(), TOLERANCE);
    }

    private List<FusedChunk> fusedList(int count) {
        List<FusedChunk> fused = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            fused.add(FusedChunk.builder()
                    .chunkId("ck_" + i)
                    .fusedScore(1.0d / (i + 1))
                    .fusionMode(FusionMode.RRF)
                    .primarySource(RetrievalSource.BM25)
                    .routeRanks(Map.of(RetrievalSource.BM25, i + 1))
                    .routeScores(Map.of(RetrievalSource.BM25, (double) (count - i)))
                    .normalizedScores(Map.of())
                    .build());
        }
        return fused;
    }

    private RetrievalCandidate candidate(String chunkId, String parentId, double fusedScore) {
        FusedChunk fused = FusedChunk.builder()
                .chunkId(chunkId)
                .fusedScore(fusedScore)
                .fusionMode(FusionMode.RRF)
                .primarySource(RetrievalSource.BM25)
                .routeRanks(Map.of(RetrievalSource.BM25, 1))
                .routeScores(Map.of(RetrievalSource.BM25, fusedScore))
                .normalizedScores(Map.of())
                .build();
        Chunk chunk = new Chunk();
        chunk.setChunkId(chunkId);
        chunk.setParentId(parentId);
        chunk.setContent("content of " + chunkId);
        chunk.setChunkType(ChunkType.TEXT);
        chunk.setEnabled(1);
        chunk.setSeq(0);
        return new RetrievalCandidate(fused, chunk);
    }
}
