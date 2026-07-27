package io.kbrag.app.chat;

import io.kbrag.common.constant.KbConstants;
import io.kbrag.domain.model.ParsedChatFile;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Decides, per conversation, whether an import creates a document or a new version of one.
 *
 * <p><b>The identity is the source channel plus the session id, never the display name.</b> A group chat
 * gets renamed, a contact gets a new remark, and two different conversations can share a name. Keying on
 * the display name would either duplicate documents on every rename or merge two unrelated conversations
 * into one document, and both mistakes are only visible after the index is wrong.
 *
 * <p>Pure decision logic on purpose: the caller supplies what already exists, so the rule that turns a
 * re-import into a version bump can be verified without a database.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
public class ChatSessionMatcher {

    private static final String IDENTITY_SEPARATOR = ":";

    /**
     * Builds the logical document identity of a conversation.
     *
     * @param sessionId conversation identifier of the source channel
     * @return stable identity stored in {@code t_kb_document.source_key}
     */
    public String sourceKeyOf(String sessionId) {
        return KbConstants.SOURCE_CHANNEL_CHAT + IDENTITY_SEPARATOR + sessionId;
    }

    /**
     * Matches every conversation of an export against the documents a knowledge base already holds.
     *
     * @param sessions           conversations found in the export
     * @param docIdBySourceKey   document id per logical identity already present in the knowledge base
     * @return one decision per conversation, in export order
     */
    public List<ChatImportView.SessionMatch> match(List<ParsedChatFile.ChatSession> sessions,
                                                   Map<String, String> docIdBySourceKey) {
        List<ChatImportView.SessionMatch> matches = new ArrayList<>();
        if (CollectionUtils.isEmpty(sessions)) {
            return matches;
        }
        for (ParsedChatFile.ChatSession session : sessions) {
            List<ParsedChatFile.ChatMessageRecord> messages = session.messagesOrEmpty();
            Long from = null;
            Long to = null;
            for (ParsedChatFile.ChatMessageRecord message : messages) {
                if (message.getSendTime() == null) {
                    continue;
                }
                from = from == null ? message.getSendTime() : Math.min(from, message.getSendTime());
                to = to == null ? message.getSendTime() : Math.max(to, message.getSendTime());
            }
            String matchedDocId = docIdBySourceKey == null
                    ? null : docIdBySourceKey.get(sourceKeyOf(session.getSessionId()));
            matches.add(ChatImportView.SessionMatch.builder()
                    .sessionId(session.getSessionId())
                    .sessionName(displayNameOf(session))
                    .messageCount(messages.size())
                    .timeFrom(from)
                    .timeTo(to)
                    .matchedDocId(matchedDocId)
                    .action(matchedDocId == null
                            ? ChatImportAction.CREATE.name() : ChatImportAction.NEW_VERSION.name())
                    .build());
        }
        log.info("chat sessions matched, sessions={}, existingDocuments={}",
                matches.size(), matches.stream().filter(match -> match.getMatchedDocId() != null).count());
        return matches;
    }

    /**
     * Display name of a conversation, falling back to its identifier.
     *
     * @param session conversation
     * @return name shown in the console and stored as the document file name
     */
    public String displayNameOf(ParsedChatFile.ChatSession session) {
        if (session.getSessionName() != null && !session.getSessionName().isBlank()) {
            return session.getSessionName();
        }
        return session.getSessionId();
    }
}
