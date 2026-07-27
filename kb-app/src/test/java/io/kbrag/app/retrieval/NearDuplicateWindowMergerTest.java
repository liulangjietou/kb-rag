package io.kbrag.app.retrieval;

import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.enums.FusionMode;
import io.kbrag.domain.enums.RetrievalSource;
import io.kbrag.domain.model.FusedChunk;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the near duplicate window merging: the ratio boundary, which of two overlapping windows survives,
 * the cap on the reported ids, and the guarantee that a chunk without a message span is untouched.
 *
 * @author owlzhangfq@gmail.com
 */
class NearDuplicateWindowMergerTest {

    private static final String SESSION = "session_1";

    private final NearDuplicateWindowMerger merger = new NearDuplicateWindowMerger();

    @Test
    void shouldKeepEverythingWhenNoCandidateIsAnAggregationWindow() {
        List<FusedChunk> ranked = List.of(fused("ck_1", 9.0d), fused("ck_2", 8.0d));
        Map<String, Chunk> chunks = chunks(plain("ck_1"), plain("ck_2"));

        NearDuplicateWindowMerger.Outcome outcome = merger.merge(ranked, chunks);

        // A knowledge base holding no chat import runs this stage as an ordered walk that keeps everything.
        assertEquals(List.of("ck_1", "ck_2"), idsOf(outcome.kept()));
        assertTrue(outcome.mergedIdsByChunk().isEmpty());
    }

    @Test
    void shouldMergeWindowsAtTheRatioBoundary() {
        // Two four message windows sharing exactly two messages: the ratio is 0.5, which merges.
        List<FusedChunk> ranked = List.of(fused("ck_1", 9.0d), fused("ck_2", 8.0d));
        Map<String, Chunk> chunks = chunks(window("ck_1", SESSION, 0, 3), window("ck_2", SESSION, 2, 5));

        NearDuplicateWindowMerger.Outcome outcome = merger.merge(ranked, chunks);

        assertEquals(List.of("ck_1"), idsOf(outcome.kept()));
        assertEquals(List.of("ck_2"), outcome.mergedIdsOf("ck_1"));
    }

    @Test
    void shouldKeepWindowsJustBelowTheRatioBoundary() {
        // One shared message out of four is a ratio of 0.25: the two windows carry different turns and
        // dropping either would lose content.
        List<FusedChunk> ranked = List.of(fused("ck_1", 9.0d), fused("ck_2", 8.0d));
        Map<String, Chunk> chunks = chunks(window("ck_1", SESSION, 0, 3), window("ck_2", SESSION, 3, 6));

        NearDuplicateWindowMerger.Outcome outcome = merger.merge(ranked, chunks);

        assertEquals(List.of("ck_1", "ck_2"), idsOf(outcome.kept()));
        assertTrue(outcome.mergedIdsOf("ck_1").isEmpty());
    }

    @Test
    void shouldKeepTheBestRankedOfTwoOverlappingWindows() {
        // The lower ranked window comes first in the list only to prove the survivor is chosen by rank
        // order rather than by chunk id or by span position.
        List<FusedChunk> ranked = List.of(fused("ck_low", 9.0d), fused("ck_high", 8.0d));
        Map<String, Chunk> chunks = chunks(window("ck_low", SESSION, 4, 9), window("ck_high", SESSION, 4, 9));

        NearDuplicateWindowMerger.Outcome outcome = merger.merge(ranked, chunks);

        assertEquals(List.of("ck_low"), idsOf(outcome.kept()));
        assertEquals(List.of("ck_high"), outcome.mergedIdsOf("ck_low"));
    }

    @Test
    void shouldNeverMergeWindowsOfDifferentConversations() {
        List<FusedChunk> ranked = List.of(fused("ck_1", 9.0d), fused("ck_2", 8.0d));
        Map<String, Chunk> chunks = chunks(window("ck_1", SESSION, 0, 9), window("ck_2", "session_2", 0, 9));

        NearDuplicateWindowMerger.Outcome outcome = merger.merge(ranked, chunks);

        assertEquals(List.of("ck_1", "ck_2"), idsOf(outcome.kept()));
    }

    @Test
    void shouldNotLetAChatWindowAbsorbAnUploadedDocumentChunk() {
        List<FusedChunk> ranked = List.of(fused("ck_window", 9.0d), fused("ck_doc", 8.0d));
        Map<String, Chunk> chunks = chunks(window("ck_window", SESSION, 0, 9), plain("ck_doc"));

        NearDuplicateWindowMerger.Outcome outcome = merger.merge(ranked, chunks);

        assertEquals(List.of("ck_window", "ck_doc"), idsOf(outcome.kept()));
        assertTrue(outcome.mergedIdsOf("ck_window").isEmpty());
    }

    @Test
    void shouldCapTheReportedIdsWithoutKeepingTheExtraCandidates() {
        List<FusedChunk> ranked = new ArrayList<>();
        List<Chunk> chunks = new ArrayList<>();
        ranked.add(fused("ck_best", 100.0d));
        chunks.add(window("ck_best", SESSION, 0, 9));
        for (int i = 0; i < NearDuplicateWindowMerger.MAX_MERGED_IDS + 3; i++) {
            String chunkId = "ck_dup_" + i;
            ranked.add(fused(chunkId, 90.0d - i));
            chunks.add(window(chunkId, SESSION, 0, 9));
        }

        NearDuplicateWindowMerger.Outcome outcome =
                merger.merge(ranked, chunks(chunks.toArray(new Chunk[0])));

        // Every duplicate leaves the ranking; only the report is capped, because the list explains a
        // ranking rather than reconstructing the recall set.
        assertEquals(List.of("ck_best"), idsOf(outcome.kept()));
        assertEquals(NearDuplicateWindowMerger.MAX_MERGED_IDS, outcome.mergedIdsOf("ck_best").size());
        assertEquals(List.of("ck_dup_0", "ck_dup_1", "ck_dup_2", "ck_dup_3", "ck_dup_4"),
                outcome.mergedIdsOf("ck_best"));
    }

    @Test
    void shouldJudgeCandidatesAgainstSurvivorsOnly() {
        // The middle window overlaps the first by half and the last by half, but the last one overlaps the
        // first by only a quarter. Judging against survivors keeps the last window, which carries turns
        // the surviving first window does not.
        List<FusedChunk> ranked = List.of(
                fused("ck_1", 9.0d), fused("ck_2", 8.0d), fused("ck_3", 7.0d));
        Map<String, Chunk> chunks = chunks(
                window("ck_1", SESSION, 0, 3), window("ck_2", SESSION, 2, 5), window("ck_3", SESSION, 3, 6));

        NearDuplicateWindowMerger.Outcome outcome = merger.merge(ranked, chunks);

        assertEquals(List.of("ck_1", "ck_3"), idsOf(outcome.kept()));
        assertEquals(List.of("ck_2"), outcome.mergedIdsOf("ck_1"));
    }

    @Test
    void shouldHandleAnEmptyRanking() {
        assertTrue(merger.merge(List.of(), Map.of()).kept().isEmpty());
        assertTrue(merger.merge(null, Map.of()).mergedIdsByChunk().isEmpty());
    }

    @Test
    void shouldKeepACandidateWhoseFactSourceRowIsMissing() {
        // Only a row the fusion stage failed to load; the ordering must not depend on this stage.
        NearDuplicateWindowMerger.Outcome outcome =
                merger.merge(List.of(fused("ck_1", 9.0d)), Map.of());

        assertEquals(List.of("ck_1"), idsOf(outcome.kept()));
    }

    private List<String> idsOf(List<FusedChunk> candidates) {
        return candidates.stream().map(FusedChunk::getChunkId).toList();
    }

    private Map<String, Chunk> chunks(Chunk... rows) {
        Map<String, Chunk> byId = new HashMap<>(rows.length);
        for (Chunk row : rows) {
            byId.put(row.getChunkId(), row);
        }
        return byId;
    }

    private FusedChunk fused(String chunkId, double score) {
        return FusedChunk.builder()
                .chunkId(chunkId)
                .fusedScore(score)
                .fusionMode(FusionMode.RRF)
                .primarySource(RetrievalSource.BM25)
                .routeRanks(Map.of())
                .routeScores(Map.of())
                .normalizedScores(Map.of())
                .build();
    }

    private Chunk window(String chunkId, String sessionId, int spanStart, int spanEnd) {
        Chunk chunk = new Chunk();
        chunk.setChunkId(chunkId);
        chunk.setMetadata("{\"session_id\":\"" + sessionId + "\",\"window_seq\":0,\"msg_span\":["
                + spanStart + "," + spanEnd + "]}");
        return chunk;
    }

    private Chunk plain(String chunkId) {
        Chunk chunk = new Chunk();
        chunk.setChunkId(chunkId);
        chunk.setMetadata("{\"tag_ids\":[]}");
        return chunk;
    }
}
