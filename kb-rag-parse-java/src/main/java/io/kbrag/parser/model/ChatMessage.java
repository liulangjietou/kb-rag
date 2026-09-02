package io.kbrag.parser.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One normalized chat message (M3-CONTRACTS.md §2.2).
 *
 * @author owlzhangfq@gmail.com
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {

    private String msgId;

    private String sender;

    /**
     * Explicitly named because a bean property derived from {@code isSelf()} would serialize as
     * {@code self}, silently breaking the contract field kb-rag-server reads.
     */
    @JsonProperty("is_self")
    private boolean isSelf;

    /** Epoch milliseconds. A message whose time could not be parsed never reaches this list. */
    private long sendTime;

    /** {@code text|image|other} - voice/video are filtered out before reaching this list. */
    private String msgType;

    private String content;
}
