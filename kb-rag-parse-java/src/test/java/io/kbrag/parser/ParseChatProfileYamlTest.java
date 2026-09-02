package io.kbrag.parser;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mapping profiles arriving over the wire (M8-CONTRACTS.md §0.7): a supplied {@code profile_yaml}
 * takes priority over {@code mapping_profile} resolving to a bundled file - which is what
 * kb-rag-server sends once profiles live in {@code t_kb_source_mapping} rather than only as files here.
 *
 * @author owlzhangfq@gmail.com
 */
class ParseChatProfileYamlTest extends ParseEndpointTestBase {

    @Test
    void profileYamlTakesPriorityOverANonexistentLocalProfile() throws Exception {
        byte[] content = ParserTestSupport.chatCsvBytes(List.of("from", "when", "body"),
                List.of(List.of("alice", "1737800000", "hello via inline profile")));
        String profileYaml = "sender:\n  - from\nsend_time:\n  - when\ncontent:\n  - body\n";

        JsonNode body = postParseChat("chat.csv", content, "csv",
                "this_profile_file_does_not_exist_on_disk", profileYaml);

        assertEquals("OK", body.get("code").asText());
        JsonNode message = body.get("data").get("sessions").get(0).get("messages").get(0);
        assertEquals("alice", message.get("sender").asText());
        assertEquals("hello via inline profile", message.get("content").asText());
    }

    @Test
    void invalidYamlFailsWithAnActionableError() throws Exception {
        byte[] content = ParserTestSupport.chatCsvBytes(List.of("from", "when", "body"),
                List.of(List.of("alice", "1737800000", "hi")));

        JsonNode body = postParseChat("chat.csv", content, "csv", null, "not: [valid: yaml");

        assertEquals("PARSE_FAILED", body.get("code").asText());
        assertTrue(body.get("data").isNull());
        assertTrue(body.get("message").asText().contains("yaml"));
    }

    @Test
    void htmlDefaultProfileIsUsedWhenNotSpecified() throws Exception {
        byte[] content = ("<div class='message'><span class='sender'>a</span>"
                + "<span class='time'>2024-01-01 10:00:00</span>"
                + "<div class='content'>hi</div></div>").getBytes(StandardCharsets.UTF_8);

        JsonNode body = postParseChat("chat.html", content, "html");

        assertEquals("OK", body.get("code").asText());
        assertEquals("hi",
                body.get("data").get("sessions").get(0).get("messages").get(0).get("content").asText());
    }

    @Test
    void headerMatchingIgnoresCaseAndWhitespace() throws Exception {
        // The reason the profile matches on a normalized key: two exports of the same source routinely
        // differ only in header casing or spacing, and neither should need its own profile.
        byte[] content = ParserTestSupport.chatCsvBytes(
                List.of("Room Name", "  create time ", "Str Content"),
                List.of(List.of("room_a", "1737800000", "hello")));
        String profileYaml = "session_id:\n  - roomname\nsend_time:\n  - CreateTime\ncontent:\n  - strcontent\n";

        JsonNode body = postParseChat("chat.csv", content, "csv", null, profileYaml);

        assertEquals("OK", body.get("code").asText());
        JsonNode session = body.get("data").get("sessions").get(0);
        assertEquals("room_a", session.get("session_id").asText());
        assertEquals("hello", session.get("messages").get(0).get("content").asText());
    }
}
