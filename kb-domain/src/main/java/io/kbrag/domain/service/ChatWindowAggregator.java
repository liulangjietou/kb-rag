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
 * <p><b>No overlap, on purpose.</b> A sliding window over chat messages would replay nearly every turn
 * in two chunks: turns are short, so the overlap would be a large share of each window and would double
 * the index for no recall benefit. The requirement fixes the sequential cut for the first release.
 *
 * <p>A window closes on whichever bound comes first, the elapsed time since its own first message or the
 * message ceiling. Closing on elapsed time measured from the window start rather than from the previous
 * message is what keeps a long silence from being swallowed into one enormous window.
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

        List<ParsedChatFile.ChatMessageRecord> ordered = sortByTime(messages);
        List<ChatWindow> windows = new ArrayList<>();
        List<ParsedChatFile.ChatMessageRecord> buffer = new ArrayList<>();
        Long windowStart = null;

        for (ParsedChatFile.ChatMessageRecord message : ordered) {
            Long sendTime = message.getSendTime();
            boolean timeBoundReached = windowStart != null && sendTime != null
                    && sendTime - windowStart >= windowMillis;
            boolean countBoundReached = buffer.size() >= maxMessages;
            if (!buffer.isEmpty() && (timeBoundReached || countBoundReached)) {
                windows.add(toWindow(windows.size(), buffer));
                buffer = new ArrayList<>();
                windowStart = null;
            }
            if (windowStart == null && sendTime != null) {
                windowStart = sendTime;
            }
            buffer.add(message);
        }
        if (!buffer.isEmpty()) {
            windows.add(toWindow(windows.size(), buffer));
        }
        log.info("chat messages aggregated, messages={}, windows={}, windowMinutes={}, maxMessages={}",
                ordered.size(), windows.size(), effective.effectiveWindowMinutes(), maxMessages);
        return windows;
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

    private ChatWindow toWindow(int seq, List<ParsedChatFile.ChatMessageRecord> buffer) {
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
        return new ChatWindow(seq, resolvedStart, resolvedEnd, new ArrayList<>(senders),
                new ArrayList<>(buffer));
    }
}
