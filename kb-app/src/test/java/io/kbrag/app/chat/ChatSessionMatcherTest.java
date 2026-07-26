package io.kbrag.app.chat;

import io.kbrag.domain.model.ParsedChatFile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the create versus new version decision and the identity it is based on.
 *
 * @author owlzhangfq@gmail.com
 */
class ChatSessionMatcherTest {

    private static final long BASE_TIME = 1_737_800_000_000L;

    private final ChatSessionMatcher matcher = new ChatSessionMatcher();

    @Test
    void shouldBuildTheIdentityFromTheChannelAndTheSessionId() {
        assertEquals("chat:room_42", matcher.sourceKeyOf("room_42"));
    }

    @Test
    void shouldReportCreateForAnUnknownConversation() {
        List<ChatImportView.SessionMatch> matches = matcher.match(
                List.of(session("room_1", "Team room", 3)), Map.of());

        assertEquals(1, matches.size());
        assertEquals(ChatImportAction.CREATE.name(), matches.get(0).getAction());
        assertNull(matches.get(0).getMatchedDocId());
    }

    @Test
    void shouldReportNewVersionForAConversationAlreadyImported() {
        List<ChatImportView.SessionMatch> matches = matcher.match(
                List.of(session("room_1", "Team room", 3)),
                Map.of("chat:room_1", "doc_existing"));

        assertEquals(ChatImportAction.NEW_VERSION.name(), matches.get(0).getAction());
        assertEquals("doc_existing", matches.get(0).getMatchedDocId());
    }

    @Test
    void shouldStillMatchAConversationThatWasRenamed() {
        List<ChatImportView.SessionMatch> matches = matcher.match(
                List.of(session("room_1", "Renamed room", 2)),
                Map.of("chat:room_1", "doc_existing"));

        // The identity is the session id, so a rename is a version bump rather than a duplicate document.
        assertEquals(ChatImportAction.NEW_VERSION.name(), matches.get(0).getAction());
        assertEquals("Renamed room", matches.get(0).getSessionName());
    }

    @Test
    void shouldNotMergeTwoConversationsSharingADisplayName() {
        List<ChatImportView.SessionMatch> matches = matcher.match(
                List.of(session("room_1", "Project", 2), session("room_2", "Project", 2)),
                Map.of("chat:room_1", "doc_existing"));

        assertEquals(ChatImportAction.NEW_VERSION.name(), matches.get(0).getAction());
        assertEquals(ChatImportAction.CREATE.name(), matches.get(1).getAction());
    }

    @Test
    void shouldReportTheMessageCountAndTheTimeRange() {
        List<ChatImportView.SessionMatch> matches = matcher.match(
                List.of(session("room_1", "Team room", 3)), Map.of());

        ChatImportView.SessionMatch match = matches.get(0);
        assertEquals(3, match.getMessageCount());
        assertEquals(BASE_TIME, match.getTimeFrom());
        assertEquals(BASE_TIME + 2, match.getTimeTo());
    }

    @Test
    void shouldLeaveTheTimeRangeEmptyWhenNoMessageCarriesATimestamp() {
        ParsedChatFile.ChatSession session = ParsedChatFile.ChatSession.builder()
                .sessionId("room_1")
                .sessionName("Team room")
                .messages(List.of(ParsedChatFile.ChatMessageRecord.builder().content("no time").build()))
                .build();

        ChatImportView.SessionMatch match = matcher.match(List.of(session), Map.of()).get(0);

        assertNull(match.getTimeFrom());
        assertNull(match.getTimeTo());
        assertEquals(1, match.getMessageCount());
    }

    @Test
    void shouldFallBackToTheSessionIdAsDisplayName() {
        List<ChatImportView.SessionMatch> matches = matcher.match(
                List.of(session("room_1", null, 1)), Map.of());

        assertEquals("room_1", matches.get(0).getSessionName());
    }

    @Test
    void shouldReturnNothingWithoutSessions() {
        assertTrue(matcher.match(List.of(), Map.of()).isEmpty());
        assertTrue(matcher.match(null, Map.of()).isEmpty());
    }

    @Test
    void shouldTolerateAMissingLookupMap() {
        List<ChatImportView.SessionMatch> matches = matcher.match(
                List.of(session("room_1", "Team room", 1)), null);

        assertEquals(ChatImportAction.CREATE.name(), matches.get(0).getAction());
    }

    private ParsedChatFile.ChatSession session(String sessionId, String sessionName, int messageCount) {
        List<ParsedChatFile.ChatMessageRecord> messages = new java.util.ArrayList<>();
        for (int i = 0; i < messageCount; i++) {
            messages.add(ParsedChatFile.ChatMessageRecord.builder()
                    .sender("alice")
                    .sendTime(BASE_TIME + i)
                    .msgType("text")
                    .content("message " + i)
                    .build());
        }
        return ParsedChatFile.ChatSession.builder()
                .sessionId(sessionId)
                .sessionName(sessionName)
                .messages(messages)
                .build();
    }
}
