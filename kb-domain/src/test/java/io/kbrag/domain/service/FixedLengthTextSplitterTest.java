package io.kbrag.domain.service;

import io.kbrag.domain.model.SplitChunk;
import io.kbrag.domain.model.SplitParams;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the fixed length splitter: budget compliance, overlap and the oversized segment fallback.
 */
class FixedLengthTextSplitterTest {

    private final SimpleTokenEstimator estimator = new SimpleTokenEstimator();
    private final FixedLengthTextSplitter splitter = new FixedLengthTextSplitter(estimator);

    @Test
    void shouldReturnEmptyListForBlankInput() {
        assertTrue(splitter.split(null, SplitParams.defaults()).isEmpty());
        assertTrue(splitter.split("   ", SplitParams.defaults()).isEmpty());
    }

    @Test
    void shouldKeepShortTextInASingleChunk() {
        List<SplitChunk> chunks = splitter.split("hello world.", SplitParams.defaults());
        assertEquals(1, chunks.size());
        assertEquals("hello world.", chunks.get(0).getContent());
        assertEquals(0, chunks.get(0).getSeq());
    }

    @Test
    void shouldRespectTheTokenBudget() {
        String text = sentences(40);
        SplitParams params = SplitParams.of(20, 5);
        List<SplitChunk> chunks = splitter.split(text, params);
        assertTrue(chunks.size() > 1);
        for (SplitChunk chunk : chunks) {
            assertTrue(estimator.estimate(chunk.getContent()) <= params.getMaxTokens(),
                    "chunk exceeded the budget: " + chunk.getTokenCount());
        }
    }

    @Test
    void shouldNumberChunksSequentially() {
        List<SplitChunk> chunks = splitter.split(sentences(40), SplitParams.of(20, 5));
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i, chunks.get(i).getSeq());
        }
    }

    @Test
    void shouldReplayTrailingSegmentsAsOverlap() {
        // Four sentences of nine estimated tokens each against a twenty token budget force a cut after
        // the second one, and the ten token overlap budget replays exactly the previous sentence.
        String text = "a".repeat(32) + ". " + "b".repeat(32) + ". "
                + "c".repeat(32) + ". " + "d".repeat(32) + ".";
        List<SplitChunk> chunks = splitter.split(text, SplitParams.of(20, 10));
        assertTrue(chunks.size() > 1);
        String firstChunk = chunks.get(0).getContent();
        String secondChunk = chunks.get(1).getContent();
        assertTrue(firstChunk.contains("bbbb") && secondChunk.contains("bbbb"),
                "expected the trailing sentence to be replayed in the next chunk");
        assertTrue(secondChunk.contains("cccc"), "expected the next chunk to carry new content too");
    }

    @Test
    void shouldHardCutASegmentLongerThanTheBudget() {
        // A single sentence of roughly forty tokens cannot be packed into a twenty token chunk, so it
        // is cut on the character position matching the budget instead of being dropped.
        String text = "a".repeat(160) + ".";
        List<SplitChunk> chunks = splitter.split(text, SplitParams.of(20, 0));
        assertTrue(chunks.size() > 1);
        for (SplitChunk chunk : chunks) {
            assertTrue(estimator.estimate(chunk.getContent()) <= 20);
        }
        String rejoined = String.join("", chunks.stream().map(SplitChunk::getContent).toList());
        assertEquals(text.length(), rejoined.length(), "hard cutting must not drop content");
    }

    @Test
    void shouldNotProduceBlankChunks() {
        List<SplitChunk> chunks = splitter.split(sentences(30), SplitParams.of(15, 3));
        for (SplitChunk chunk : chunks) {
            assertFalse(chunk.getContent().isBlank());
        }
    }

    @Test
    void shouldRejectOverlapNotSmallerThanTheBudget() {
        assertThrows(IllegalArgumentException.class, () -> SplitParams.of(100, 100));
        assertThrows(IllegalArgumentException.class, () -> SplitParams.of(0, 0));
    }

    private String sentences(int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            builder.append("sentence number ").append(i).append(" of the sample corpus. ");
        }
        return builder.toString();
    }
}
