package io.kbrag.parser;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chat log parsing over csv/xlsx with the built-in {@code memotrace} mapping profile
 * (M3-CONTRACTS.md §2.2).
 *
 * @author owlzhangfq@gmail.com
 */
class ParseChatTabularTest extends ParseEndpointTestBase {

    private static final List<String> HEADER =
            List.of("room_name", "NickName", "Sender", "IsSender", "CreateTime", "Type", "StrContent");

    private static void assertSkipped(JsonNode data, int voice, int video, int other) {
        JsonNode skipped = data.get("skipped");
        assertEquals(voice, skipped.get("voice").asInt());
        assertEquals(video, skipped.get("video").asInt());
        assertEquals(other, skipped.get("other").asInt());
    }

    @Test
    void parseChatCsvPositive() throws Exception {
        byte[] content = ParserTestSupport.chatCsvBytes(HEADER, List.of(
                List.of("room_a", "Alice's Room", "alice", "1", "1737800000", "1", "hello there"),
                List.of("room_a", "Alice's Room", "bob", "0", "1737800060", "1", "hi alice")));

        JsonNode body = postParseChat("chat.csv", content, "csv");

        assertEquals("OK", body.get("code").asText());
        JsonNode data = body.get("data");

        assertEquals(1, data.get("sessions").size());
        JsonNode session = data.get("sessions").get(0);
        assertEquals("room_a", session.get("session_id").asText());
        assertEquals("Alice's Room", session.get("session_name").asText());
        assertEquals(2, session.get("messages").size());

        JsonNode first = session.get("messages").get(0);
        assertEquals("alice", first.get("sender").asText());
        assertTrue(first.get("is_self").asBoolean());
        assertEquals("text", first.get("msg_type").asText());
        assertEquals("hello there", first.get("content").asText());
        // epoch seconds normalized to milliseconds
        assertEquals(1737800000000L, first.get("send_time").asLong());

        assertFalse(session.get("messages").get(1).get("is_self").asBoolean());
        assertSkipped(data, 0, 0, 0);
    }

    @Test
    void parseChatXlsxPositive() throws Exception {
        byte[] content = ParserTestSupport.chatXlsxBytes(HEADER, List.of(
                List.of("room_b", "Team Chat", "carol", "1", "1737800000000", "1", "xlsx hello")));

        JsonNode body = postParseChat("chat.xlsx", content, "xlsx");

        assertEquals("OK", body.get("code").asText());
        JsonNode session = body.get("data").get("sessions").get(0);
        assertEquals("room_b", session.get("session_id").asText());
        assertEquals(1, session.get("messages").size());
        JsonNode message = session.get("messages").get(0);
        assertEquals("xlsx hello", message.get("content").asText());
        // epoch milliseconds passed straight through
        assertEquals(1737800000000L, message.get("send_time").asLong());
    }

    @Test
    void missingContentColumnFails() throws Exception {
        // No candidate for the one hard requirement.
        List<String> header = List.of("room_name", "NickName", "Sender", "IsSender", "CreateTime", "Type");
        byte[] content = ParserTestSupport.chatCsvBytes(header,
                List.of(List.of("room_a", "Alice's Room", "alice", "1", "1737800000", "1")));

        JsonNode body = postParseChat("chat.csv", content, "csv");

        assertEquals("PARSE_FAILED", body.get("code").asText());
        assertTrue(body.get("data").isNull());
        assertTrue(body.get("message").asText().contains("content"));
    }

    @Test
    void sendTimeFormatsAreAllNormalizedToEpochMs() throws Exception {
        byte[] content = ParserTestSupport.chatCsvBytes(HEADER, List.of(
                List.of("room_a", "Room", "alice", "1", "1737800000", "1", "epoch seconds"),
                List.of("room_a", "Room", "alice", "1", "1737800000000", "1", "epoch millis"),
                List.of("room_a", "Room", "alice", "1", "2025-01-25 10:13:20", "1", "string datetime")));

        JsonNode body = postParseChat("chat.csv", content, "csv");

        assertEquals("OK", body.get("code").asText());
        JsonNode messages = body.get("data").get("sessions").get(0).get("messages");
        assertEquals(3, messages.size());
        assertEquals(1737800000000L, messages.get(0).get("send_time").asLong());
        assertEquals(1737800000000L, messages.get(1).get("send_time").asLong());
        // Exact value is timezone-dependent; it only has to parse.
        assertTrue(messages.get(2).get("send_time").asLong() > 0);
    }

    @Test
    void unparseableSendTimeIsSkippedAsOther() throws Exception {
        byte[] content = ParserTestSupport.chatCsvBytes(HEADER, List.of(
                List.of("room_a", "Room", "alice", "1", "not-a-real-timestamp", "1", "bad time"),
                List.of("room_a", "Room", "alice", "1", "1737800000", "1", "good time")));

        JsonNode body = postParseChat("chat.csv", content, "csv");

        JsonNode data = body.get("data");
        assertEquals(1, data.get("sessions").get(0).get("messages").size());
        assertEquals("good time",
                data.get("sessions").get(0).get("messages").get(0).get("content").asText());
        assertSkipped(data, 0, 0, 1);
    }

    @Test
    void voiceAndVideoMessagesAreSkippedButCounted() throws Exception {
        byte[] content = ParserTestSupport.chatCsvBytes(HEADER, List.of(
                List.of("room_a", "Room", "alice", "1", "1737800000", "1", "text message"),
                List.of("room_a", "Room", "alice", "1", "1737800001", "34", "[voice message]"),
                List.of("room_a", "Room", "alice", "1", "1737800002", "43", "[video message]"),
                List.of("room_a", "Room", "alice", "1", "1737800003", "3", "[image message]")));

        JsonNode body = postParseChat("chat.csv", content, "csv");

        JsonNode data = body.get("data");
        JsonNode messages = data.get("sessions").get(0).get("messages");
        List<String> msgTypes = new java.util.ArrayList<>();
        messages.forEach(message -> msgTypes.add(message.get("msg_type").asText()));

        assertFalse(msgTypes.contains("voice"));
        assertFalse(msgTypes.contains("video"));
        assertTrue(msgTypes.contains("image"));
        assertTrue(msgTypes.contains("text"));
        assertEquals(2, messages.size(), "text + image only");
        assertSkipped(data, 1, 1, 0);
    }

    @Test
    void multipleRoomsBecomeMultipleSessionsInFirstAppearanceOrder() throws Exception {
        byte[] content = ParserTestSupport.chatCsvBytes(HEADER, List.of(
                List.of("room_b", "B", "alice", "1", "1737800000", "1", "in b"),
                List.of("room_a", "A", "bob", "0", "1737800001", "1", "in a"),
                List.of("room_b", "B", "carol", "0", "1737800002", "1", "also in b")));

        JsonNode sessions = postParseChat("chat.csv", content, "csv").get("data").get("sessions");

        assertEquals(2, sessions.size());
        assertEquals("room_b", sessions.get(0).get("session_id").asText());
        assertEquals(2, sessions.get(0).get("messages").size());
        assertEquals("room_a", sessions.get(1).get("session_id").asText());
    }

    @Test
    void unknownMappingProfileFails() throws Exception {
        byte[] content = ParserTestSupport.chatCsvBytes(HEADER,
                List.of(List.of("room_a", "Room", "alice", "1", "1737800000", "1", "hi")));

        JsonNode body = postParseChat("chat.csv", content, "csv", "does_not_exist", null);

        assertEquals("PARSE_FAILED", body.get("code").asText());
        assertTrue(body.get("data").isNull());
        assertTrue(body.get("message").asText().contains("does_not_exist"));
    }
}
