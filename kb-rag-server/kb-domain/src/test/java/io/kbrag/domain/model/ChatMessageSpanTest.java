package io.kbrag.domain.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Covers the span reconstruction from stored metadata and the overlap ratio the near duplicate merging
 * keys on, including the ratio boundary itself.
 *
 * @author owlzhangfq@gmail.com
 */
class ChatMessageSpanTest {

    private static final String SESSION = "session_1";
    private static final double PRECISION = 1e-9d;

    @Test
    void shouldReadASpanOutOfStoredMetadata() {
        ChatMessageSpan span = ChatMessageSpan.from(SESSION, List.of(3, 7));

        assertEquals(SESSION, span.sessionId());
        assertEquals(3, span.start());
        assertEquals(7, span.end());
        assertEquals(5, span.size());
    }

    @Test
    void shouldReadASpanStoredAsJsonNumbers() {
        // A metadata document read back through Jackson yields Integer or Long, never int.
        ChatMessageSpan span = ChatMessageSpan.from(SESSION, List.of(0L, 4L));

        assertEquals(0, span.start());
        assertEquals(4, span.end());
    }

    @Test
    void shouldYieldNothingForAChunkThatIsNotAnAggregationWindow() {
        assertNull(ChatMessageSpan.from(null, List.of(0, 1)));
        assertNull(ChatMessageSpan.from("", List.of(0, 1)));
        assertNull(ChatMessageSpan.from(SESSION, null));
        assertNull(ChatMessageSpan.from(SESSION, "0,1"));
    }

    @Test
    void shouldYieldNothingForAnUnusableSpan() {
        assertNull(ChatMessageSpan.from(SESSION, List.of(1)));
        assertNull(ChatMessageSpan.from(SESSION, List.of(1, 2, 3)));
        assertNull(ChatMessageSpan.from(SESSION, List.of(5, 2)));
        assertNull(ChatMessageSpan.from(SESSION, List.of(-1, 2)));
        assertNull(ChatMessageSpan.from(SESSION, List.of("a", "b")));
    }

    @Test
    void shouldDivideByTheSmallerWindow() {
        ChatMessageSpan wide = new ChatMessageSpan(SESSION, 0, 9);
        ChatMessageSpan narrow = new ChatMessageSpan(SESSION, 2, 5);

        // The narrow window sits entirely inside the wide one, so it is fully covered whichever way the
        // pair is compared - a wider neighbour must never dilute the ratio.
        assertEquals(1.0d, wide.overlapRatio(narrow), PRECISION);
        assertEquals(1.0d, narrow.overlapRatio(wide), PRECISION);
    }

    @Test
    void shouldReachTheThresholdExactlyAtHalfTheSmallerWindow() {
        ChatMessageSpan first = new ChatMessageSpan(SESSION, 0, 3);
        // Messages 2 and 3 are shared out of a four message window: exactly one half.
        ChatMessageSpan second = new ChatMessageSpan(SESSION, 2, 5);

        assertEquals(0.5d, first.overlapRatio(second), PRECISION);
    }

    @Test
    void shouldStayBelowTheThresholdForASingleSharedMessage() {
        ChatMessageSpan first = new ChatMessageSpan(SESSION, 0, 3);
        ChatMessageSpan second = new ChatMessageSpan(SESSION, 3, 6);

        assertEquals(0.25d, first.overlapRatio(second), PRECISION);
    }

    @Test
    void shouldReportNoOverlapForDisjointRanges() {
        ChatMessageSpan first = new ChatMessageSpan(SESSION, 0, 3);
        ChatMessageSpan second = new ChatMessageSpan(SESSION, 4, 7);

        assertEquals(0.0d, first.overlapRatio(second), PRECISION);
    }

    @Test
    void shouldNeverOverlapAcrossConversations() {
        ChatMessageSpan first = new ChatMessageSpan(SESSION, 0, 9);
        ChatMessageSpan other = new ChatMessageSpan("session_2", 0, 9);

        // Two conversations number their messages independently, so identical ranges mean nothing.
        assertEquals(0.0d, first.overlapRatio(other), PRECISION);
        assertEquals(0.0d, first.overlapRatio(null), PRECISION);
    }
}
