package io.kbrag.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parser response of a chat export, one entry per conversation.
 *
 * <p>The type is serialised into object storage between the match preview and the confirmation, so the
 * import decision a user sees is the one that gets executed: re-parsing on confirmation could yield a
 * different session split if the mapping profile changed in between.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
@JsonIgnoreProperties(ignoreUnknown = true)
public class ParsedChatFile {

    /** Conversations found in the export. */
    @JsonProperty("sessions")
    @Builder.Default
    private List<ChatSession> sessions = new ArrayList<>();

    /** Messages dropped per reason, for example voice and video which are out of scope. */
    @JsonProperty("skipped")
    @Builder.Default
    private Map<String, Integer> skipped = Map.of();

    /**
     * Sessions, never {@code null}.
     *
     * @return conversations
     */
    public List<ChatSession> sessionsOrEmpty() {
        return sessions == null ? List.of() : sessions;
    }

    /**
     * Skipped counters, never {@code null}.
     *
     * @return dropped message counters
     */
    public Map<String, Integer> skippedOrEmpty() {
        return skipped == null ? Map.of() : skipped;
    }

    /**
     * One conversation.
     */
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @ToString
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ChatSession {

        /** Stable conversation identifier of the source channel. */
        @JsonProperty("session_id")
        private String sessionId;

        /** Display name of the conversation. */
        @JsonProperty("session_name")
        private String sessionName;

        /** Messages in chronological order. */
        @JsonProperty("messages")
        @Builder.Default
        private List<ChatMessageRecord> messages = new ArrayList<>();

        /**
         * Messages, never {@code null}.
         *
         * @return conversation messages
         */
        public List<ChatMessageRecord> messagesOrEmpty() {
            return messages == null ? List.of() : messages;
        }
    }

    /**
     * One chat message.
     */
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @ToString(exclude = "content")
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ChatMessageRecord {

        /** Message identifier of the source channel, informational. */
        @JsonProperty("msg_id")
        private String msgId;

        /** Display name of the sender. */
        @JsonProperty("sender")
        private String sender;

        /** Set when the export owner sent the message. */
        @JsonProperty("is_self")
        private boolean self;

        /** Send time in epoch milliseconds. */
        @JsonProperty("send_time")
        private Long sendTime;

        /** Message nature, {@code text} or {@code image}; voice and video are dropped by the parser. */
        @JsonProperty("msg_type")
        private String msgType;

        /** Message body. */
        @JsonProperty("content")
        private String content;
    }
}
