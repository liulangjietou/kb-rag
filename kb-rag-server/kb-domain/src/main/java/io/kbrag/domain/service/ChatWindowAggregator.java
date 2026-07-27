package io.kbrag.domain.service;

import io.kbrag.domain.model.ChatAggregationParams;
import io.kbrag.domain.model.ChatWindow;
import io.kbrag.domain.model.ParsedChatFile;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Cuts a conversation into consecutive windows.
 *
 * <p>A window closes on whichever bound comes first, the elapsed time since its own first message or the
 * message ceiling. Closing on elapsed time measured from the window start rather than from the previous
 * message is what keeps a long silence from being swallowed into one enormous window.
 *
 * <p><b>Overlap slides the start, it does not extend the window.</b> With
 * {@code window_overlap = k} the next window begins {@code k} messages before the previous one ended, so
 * every window still respects both closing bounds and only its starting point moves. Extending a closed
 * window backwards instead would produce windows above the configured message ceiling, which is the one
 * number an operator sizes the chunk budget with. {@code window_overlap = 0} reproduces the sequential cut
 * of the first release exactly, which is why it stays the default.
 *
 * <p>Messages without a timestamp are kept and treated as belonging to the window being filled: dropping
 * them would lose content, and a chat export with a broken time column is common enough that it must not
 * cost data.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
public class ChatWindowAggregator {

    private static final long MILLIS_PER_MINUTE = 60_000L;
    private static final String UNKNOWN_SENDER = "unknown";

    /** Smallest number of messages a window may advance by, which guarantees the walk terminates. */
    private static final int MIN_STEP = 1;

    /**
     * Aggregates one conversation.
     *
     * @param messages conversation messages, any order
     * @param params   window parameters
     * @return windows in chronological order, empty when there is nothing to aggregate
     */
    public List<ChatWindow> aggregate(List<ParsedChatFile.ChatMessageRecord> messages,
                                      ChatAggregationParams params) {
        if (CollectionUtils.isEmpty(messages)) {
            return List.of();
        }
        ChatAggregationParams effective = params == null ? ChatAggregationParams.defaults() : params;
        long windowMillis = effective.effectiveWindowMinutes() * MILLIS_PER_MINUTE;
        int maxMessages = effective.effectiveMaxMessages();
        int overlap = effective.effectiveWindowOverlap();

        List<ParsedChatFile.ChatMessageRecord> ordered = sortByTime(messages);
        List<ChatWindow> windows = new ArrayList<>();
        int start = 0;
        while (start < ordered.size()) {
            int end = closeWindow(ordered, start, windowMillis, maxMessages);
            windows.add(toWindow(windows.size(), ordered.subList(start, end), start));
            if (end >= ordered.size()) {
                break;
            }
            start += Math.max(MIN_STEP, end - start - overlap);
        }
        log.info("chat messages aggregated, messages={}, windows={}, windowMinutes={}, maxMessages={}, "
                        + "windowOverlap={}",
                ordered.size(), windows.size(), effective.effectiveWindowMinutes(), maxMessages, overlap);
        return windows;
    }

    /**
     * Finds where the window starting at one message has to close.
     *
     * @param ordered      conversation in chronological order
     * @param start        index of the first message of the window
     * @param windowMillis elapsed time bound in milliseconds
     * @param maxMessages  message bound
     * @return exclusive end index, always greater than {@code start}
     */
    private int closeWindow(List<ParsedChatFile.ChatMessageRecord> ordered, int start, long windowMillis,
                            int maxMessages) {
        Long windowStart = null;
        int end = start;
        while (end < ordered.size()) {
            Long sendTime = ordered.get(end).getSendTime();
            boolean timeBoundReached = windowStart != null && sendTime != null
                    && sendTime - windowStart >= windowMillis;
            boolean countBoundReached = end - start >= maxMessages;
            if (timeBoundReached || countBoundReached) {
                break;
            }
            if (windowStart == null && sendTime != null) {
                windowStart = sendTime;
            }
            end++;
        }
        return end;
    }

    /**
     * Orders the messages chronologically, keeping the ones without a timestamp where they were.
     *
     * @param messages conversation messages
     * @return ordered copy
     */
    private List<ParsedChatFile.ChatMessageRecord> sortByTime(
            List<ParsedChatFile.ChatMessageRecord> messages) {
        List<ParsedChatFile.ChatMessageRecord> ordered = new ArrayList<>(messages);
        ordered.sort(Comparator.comparingLong(
                message -> message.getSendTime() == null ? Long.MAX_VALUE : message.getSendTime()));
        return ordered;
    }

    /**
     * Builds one window out of the messages it covers.
     *
     * @param seq        zero based position of the window inside the conversation
     * @param buffer     messages of the window, in chronological order
     * @param spanStart  zero based index of the first message inside the conversation
     * @return window carrying its filterable facts and its message span
     */
    private ChatWindow toWindow(int seq, List<ParsedChatFile.ChatMessageRecord> buffer, int spanStart) {
        Set<String> senders = new LinkedHashSet<>();
        long start = Long.MAX_VALUE;
        long end = Long.MIN_VALUE;
        for (ParsedChatFile.ChatMessageRecord message : buffer) {
            senders.add(message.getSender() == null || message.getSender().isBlank()
                    ? UNKNOWN_SENDER : message.getSender());
            if (message.getSendTime() == null) {
                continue;
            }
            start = Math.min(start, message.getSendTime());
            end = Math.max(end, message.getSendTime());
        }
        long resolvedStart = start == Long.MAX_VALUE ? 0L : start;
        long resolvedEnd = end == Long.MIN_VALUE ? resolvedStart : end;
        return new ChatWindow(seq, resolvedStart, resolvedEnd, spanStart, spanStart + buffer.size() - 1,
                new ArrayList<>(senders), new ArrayList<>(buffer));
    }
}
