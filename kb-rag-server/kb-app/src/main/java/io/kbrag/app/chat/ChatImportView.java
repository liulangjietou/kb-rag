package io.kbrag.app.chat;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.util.List;

/**
 * Match preview of a chat import: what the confirmation would do, session by session.
 *
 * <p>Nothing is persisted at this point. The preview exists because a chat export is ambiguous by
 * nature — the same conversation may already be in the knowledge base under a different display name —
 * and an import that silently created duplicates would be discovered only after the index was polluted.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Builder
@ToString
public class ChatImportView {

    /** Token identifying the staged upload, required by the confirmation call. */
    private final String uploadToken;

    /** One entry per conversation found in the export. */
    private final List<SessionMatch> sessions;

    /** Messages the parser dropped, per reason. */
    private final java.util.Map<String, Integer> skipped;

    /**
     * What the confirmation would do with one conversation.
     */
    @Getter
    @Builder
    @ToString
    public static class SessionMatch {

        /** Conversation identifier of the source channel. */
        private final String sessionId;

        /** Display name of the conversation. */
        private final String sessionName;

        /** Number of messages that would be imported. */
        private final int messageCount;

        /** Send time of the first message, in epoch milliseconds. */
        private final Long timeFrom;

        /** Send time of the last message, in epoch milliseconds. */
        private final Long timeTo;

        /** Document the conversation already maps to, {@code null} when it is new. */
        private final String matchedDocId;

        /** {@code CREATE} for a new document, {@code NEW_VERSION} for an existing one. */
        private final String action;
    }
}
