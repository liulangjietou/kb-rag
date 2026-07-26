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

        // Every message appears exactly once: the requirement rules out a sliding window for the first
        // release, and a duplicated turn would inflate the index for no recall gain.
        int total = windows.stream().mapToInt(window -> window.getMessages().size()).sum();
        assertEquals(10, total);
        assertEquals(List.of(0, 1, 2), List.of(windows.get(0).getSeq(), windows.get(1).getSeq(),
                windows.get(2).getSeq()));
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
        ChatAggregationParams params = new ChatAggregationParams();
        params.setWindowMinutes(windowMinutes);
        params.setMaxMessages(maxMessages);
        return params;
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
