package io.kbrag.domain.service;

import io.kbrag.domain.model.ChatAggregationParams;
import io.kbrag.domain.model.ChatWindow;
import io.kbrag.domain.model.ParsedChatFile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the window boundaries: the two closing conditions, the absence of overlap, and the degenerate
 * inputs a real export produces.
 *
 * @author owlzhangfq@gmail.com
 */
class ChatWindowAggregatorTest {

    private static final long BASE_TIME = 1_737_800_000_000L;
    private static final long MINUTE = 60_000L;

    private final ChatWindowAggregator aggregator = new ChatWindowAggregator();

    @Test
    void shouldReturnNothingForAnEmptyConversation() {
        assertTrue(aggregator.aggregate(List.of(), params(60, 50)).isEmpty());
        assertTrue(aggregator.aggregate(null, params(60, 50)).isEmpty());
    }

    @Test
    void shouldProduceOneWindowForASingleMessage() {
        List<ChatWindow> windows = aggregator.aggregate(
                List.of(message("alice", BASE_TIME, "hello")), params(60, 50));

        assertEquals(1, windows.size());
        assertEquals(1, windows.get(0).getMessages().size());
        assertEquals(BASE_TIME, windows.get(0).getStartTime());
        assertEquals(BASE_TIME, windows.get(0).getEndTime());
        assertEquals(List.of("alice"), windows.get(0).getSenders());
    }

    @Test
    void shouldKeepMessagesInsideTheTimeWindowTogether() {
        List<ChatWindow> windows = aggregator.aggregate(List.of(
                message("alice", BASE_TIME, "one"),
                message("bob", BASE_TIME + 30 * MINUTE, "two"),
                message("alice", BASE_TIME + 59 * MINUTE, "three")), params(60, 50));

        assertEquals(1, windows.size());
        assertEquals(3, windows.get(0).getMessages().size());
        assertEquals(List.of("alice", "bob"), windows.get(0).getSenders());
    }

    @Test
    void shouldCloseTheWindowOnTheTimeBound() {
        List<ChatWindow> windows = aggregator.aggregate(List.of(
                message("alice", BASE_TIME, "one"),
                message("alice", BASE_TIME + 59 * MINUTE, "two"),
                message("alice", BASE_TIME + 60 * MINUTE, "three")), params(60, 50));

        assertEquals(2, windows.size());
        assertEquals(2, windows.get(0).getMessages().size());
        // The bound is measured from the first message of the window, so a long conversation is cut evenly
        // instead of being swallowed whole by a chain of short gaps.
        assertEquals(BASE_TIME + 60 * MINUTE, windows.get(1).getStartTime());
    }

    @Test
    void shouldCloseTheWindowOnTheMessageBound() {
        List<ParsedChatFile.ChatMessageRecord> messages = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            messages.add(message("alice", BASE_TIME + i, "message " + i));
        }

        List<ChatWindow> windows = aggregator.aggregate(messages, params(60, 3));

        assertEquals(3, windows.size());
        assertEquals(3, windows.get(0).getMessages().size());
        assertEquals(3, windows.get(1).getMessages().size());
        assertEquals(1, windows.get(2).getMessages().size());
    }

    @Test
    void shouldNotOverlapWindows() {
        List<ParsedChatFile.ChatMessageRecord> messages = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            messages.add(message("alice", BASE_TIME + i, "message " + i));
        }

        List<ChatWindow> windows = aggregator.aggregate(messages, params(60, 4));

        // Zero overlap is the default and reproduces the sequential cut of the first release exactly:
        // every message appears once and no window repeats a turn of its neighbour.
        int total = windows.stream().mapToInt(window -> window.getMessages().size()).sum();
        assertEquals(10, total);
        assertEquals(List.of(0, 1, 2), List.of(windows.get(0).getSeq(), windows.get(1).getSeq(),
                windows.get(2).getSeq()));
    }

    @Test
    void shouldReportTheMessageSpanOfEveryWindow() {
        List<ChatWindow> windows = aggregator.aggregate(messages(7), params(60, 3));

        assertEquals(3, windows.size());
        assertEquals(List.of(0, 2), spanOf(windows.get(0)));
        assertEquals(List.of(3, 5), spanOf(windows.get(1)));
        // The tail window is short, so its span is a single message rather than a padded range.
        assertEquals(List.of(6, 6), spanOf(windows.get(2)));
    }

    @Test
    void shouldRepeatTheTrailingMessagesWhenOverlapIsConfigured() {
        List<ChatWindow> windows = aggregator.aggregate(messages(10), params(60, 5, 2));

        assertEquals(3, windows.size());
        assertEquals(List.of(0, 4), spanOf(windows.get(0)));
        // The second window starts two messages before the first one ended, which is the configured overlap.
        assertEquals(List.of(3, 7), spanOf(windows.get(1)));
        assertEquals(List.of(6, 9), spanOf(windows.get(2)));
        assertEquals("message 3", windows.get(1).getMessages().get(0).getContent());
    }

    @Test
    void shouldNeverExceedTheMessageCeilingWhenOverlapping() {
        List<ChatWindow> windows = aggregator.aggregate(messages(20), params(60, 5, 2));

        // The overlap slides the start, it does not widen the window: the ceiling an operator sized the
        // chunk budget with still holds for every window.
        assertTrue(windows.stream().allMatch(window -> window.getMessages().size() <= 5));
        // Every message is still covered, and the seq numbering stays dense.
        assertEquals(windows.size() - 1, windows.get(windows.size() - 1).getSeq());
        assertEquals(19, windows.get(windows.size() - 1).getMsgSpanEnd());
    }

    @Test
    void shouldClampAnOverlapTheMessageCeilingCannotCarry() {
        // The write path rejects this combination; a knowledge base that somehow holds it must still make
        // progress rather than repeat one window forever.
        List<ChatWindow> windows = aggregator.aggregate(messages(6), params(60, 3, 9));

        int covered = windows.get(windows.size() - 1).getMsgSpanEnd();
        assertEquals(5, covered);
        assertTrue(windows.size() <= 6);
    }

    @Test
    void shouldKeepTheSequentialCutWhenOverlapIsZero() {
        List<ChatWindow> withoutOverlap = aggregator.aggregate(messages(10), params(60, 4));
        List<ChatWindow> withExplicitZero = aggregator.aggregate(messages(10), params(60, 4, 0));

        assertEquals(withoutOverlap.size(), withExplicitZero.size());
        for (int i = 0; i < withoutOverlap.size(); i++) {
            assertEquals(spanOf(withoutOverlap.get(i)), spanOf(withExplicitZero.get(i)));
        }
    }

    @Test
    void shouldOrderMessagesChronologicallyBeforeWindowing() {
        List<ChatWindow> windows = aggregator.aggregate(List.of(
                message("alice", BASE_TIME + 2 * MINUTE, "later"),
                message("bob", BASE_TIME, "earlier")), params(60, 50));

        assertEquals(1, windows.size());
        assertEquals("earlier", windows.get(0).getMessages().get(0).getContent());
        assertEquals(BASE_TIME, windows.get(0).getStartTime());
    }

    @Test
    void shouldKeepMessagesWithoutATimestamp() {
        List<ChatWindow> windows = aggregator.aggregate(List.of(
                message("alice", BASE_TIME, "timed"),
                message("bob", null, "untimed")), params(60, 50));

        // A broken time column is common in real exports and must not cost content.
        int total = windows.stream().mapToInt(window -> window.getMessages().size()).sum();
        assertEquals(2, total);
    }

    @Test
    void shouldFallBackToTheDefaultsForUnusableParameters() {
        List<ChatWindow> windows = aggregator.aggregate(
                List.of(message("alice", BASE_TIME, "hello")), params(0, 0));

        assertEquals(1, windows.size());
        assertEquals(ChatAggregationParams.DEFAULT_WINDOW_MINUTES,
                ChatAggregationParams.defaults().effectiveWindowMinutes());
    }

    @Test
    void shouldFallBackToTheDefaultsForMissingParameters() {
        assertEquals(1, aggregator.aggregate(
                List.of(message("alice", BASE_TIME, "hello")), null).size());
    }

    @Test
    void shouldReportAnUnknownSenderRatherThanDroppingTheMessage() {
        List<ChatWindow> windows = aggregator.aggregate(
                List.of(message(null, BASE_TIME, "anonymous")), params(60, 50));

        assertEquals(List.of("unknown"), windows.get(0).getSenders());
    }

    private ChatAggregationParams params(int windowMinutes, int maxMessages) {
        return params(windowMinutes, maxMessages, 0);
    }

    private ChatAggregationParams params(int windowMinutes, int maxMessages, int windowOverlap) {
        ChatAggregationParams params = new ChatAggregationParams();
        params.setWindowMinutes(windowMinutes);
        params.setMaxMessages(maxMessages);
        params.setWindowOverlap(windowOverlap);
        return params;
    }

    private List<ParsedChatFile.ChatMessageRecord> messages(int count) {
        List<ParsedChatFile.ChatMessageRecord> messages = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            messages.add(message("alice", BASE_TIME + i, "message " + i));
        }
        return messages;
    }

    private List<Integer> spanOf(ChatWindow window) {
        return List.of(window.getMsgSpanStart(), window.getMsgSpanEnd());
    }

    private ParsedChatFile.ChatMessageRecord message(String sender, Long sendTime, String content) {
        return ParsedChatFile.ChatMessageRecord.builder()
                .sender(sender)
                .sendTime(sendTime)
                .msgType("text")
                .content(content)
                .build();
    }
}
