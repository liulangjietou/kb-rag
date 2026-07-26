package io.kbrag.domain.model;

import lombok.Getter;
import lombok.ToString;

import java.util.List;

/**
 * One aggregation window of a chat import: the messages it holds plus the filterable facts the
 * retrieval side needs.
 *
 * <p>The sender list and the start time are not decoration. They become
 * {@code metadata.sender} and {@code metadata.msg_time}, which are the engine side fields the M2
 * metadata filter queries, so a window that does not carry them cannot be found by conversation and
 * date.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@ToString(exclude = "messages")
public class ChatWindow {

    /** Zero based position of the window inside the conversation. */
    private final int seq;

    /** Send time of the first message, in epoch milliseconds. */
    private final long startTime;

    /** Send time of the last message, in epoch milliseconds. */
    private final long endTime;

    /** Distinct senders in first appearance order. */
    private final List<String> senders;

    /** Messages of the window, in chronological order. */
    private final List<ParsedChatFile.ChatMessageRecord> messages;

    public ChatWindow(int seq, long startTime, long endTime, List<String> senders,
                      List<ParsedChatFile.ChatMessageRecord> messages) {
        this.seq = seq;
        this.startTime = startTime;
        this.endTime = endTime;
        this.senders = senders;
        this.messages = messages;
    }
}
