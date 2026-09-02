package io.kbrag.parser;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The TXT line-template adapter (M8-CONTRACTS.md §0.1): the built-in liuhen / wechat_pc templates,
 * multi-line merge, a custom regex supplied over the wire, and the unmatched-line fast-fail.
 *
 * @author owlzhangfq@gmail.com
 */
class ParseChatTxtTest extends ParseEndpointTestBase {

    private static byte[] txt(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void liuhenTemplateMergesAMultiLineBody() throws Exception {
        byte[] content = txt("""
                2024-01-01 10:00:00 张三
                你好，最近怎么样？

                2024-01-01 10:05:00 李四
                挺好的，你呢？
                第二行内容
                """);

        JsonNode body = postParseChat("chat.txt", content, "txt");

        assertEquals("OK", body.get("code").asText());
        JsonNode data = body.get("data");

        assertEquals(1, data.get("sessions").size());
        JsonNode session = data.get("sessions").get(0);
        assertEquals("chat", session.get("session_id").asText());
        assertEquals(2, session.get("messages").size());

        JsonNode first = session.get("messages").get(0);
        assertEquals("张三", first.get("sender").asText());
        assertEquals("text", first.get("msg_type").asText());
        assertEquals("你好，最近怎么样？", first.get("content").asText());

        JsonNode second = session.get("messages").get(1);
        assertEquals("李四", second.get("sender").asText());
        // The continuation line merged into the message it belongs to.
        assertEquals("挺好的，你呢？\n第二行内容", second.get("content").asText());

        assertEquals(0, data.get("skipped").get("other").asInt());
    }

    @Test
    void wechatPcTemplateReadsAnInlineBody() throws Exception {
        byte[] content = txt("""
                张三 (2024-01-01 10:00:00): 你好
                李四 (2024-01-01 10:05:00): 挺好的，你呢？
                """);

        JsonNode body = postParseChat("chat.txt", content, "txt");

        assertEquals("OK", body.get("code").asText());
        JsonNode messages = body.get("data").get("sessions").get(0).get("messages");
        assertEquals(2, messages.size());
        assertEquals("张三", messages.get(0).get("sender").asText());
        assertEquals("你好", messages.get(0).get("content").asText());
        assertEquals("李四", messages.get(1).get("sender").asText());
        assertEquals("挺好的，你呢？", messages.get(1).get("content").asText());
    }

    @Test
    void customRegexViaProfileYamlOverridesTheBuiltins() throws Exception {
        // A line shape deliberately unlike both built-in templates, written in Python's own named-group
        // spelling - which is what a profile stored in t_kb_source_mapping actually contains.
        byte[] content = txt("[2024-01-01 10:00:00] alice >> hello there\n");
        String profileYaml = """
                txt:
                  patterns:
                    - name: custom
                      regex: '^\\[(?P<send_time>\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})\\] (?P<sender>\\S+) >> (?P<content>.*)$'
                """;

        JsonNode body = postParseChat("chat.txt", content, "txt", null, profileYaml);

        assertEquals("OK", body.get("code").asText());
        JsonNode messages = body.get("data").get("sessions").get(0).get("messages");
        assertEquals(1, messages.size());
        assertEquals("alice", messages.get(0).get("sender").asText());
        assertEquals("hello there", messages.get(0).get("content").asText());
    }

    @Test
    void wrongFormatFailsWithAnActionableError() throws Exception {
        // Plain prose, with no line ever matching a configured header template.
        byte[] content = txt("""
                This is just some random text file.
                It has multiple lines.
                None of them look like a chat log export at all.
                Not even close to the expected line templates.
                """);

        JsonNode body = postParseChat("notes.txt", content, "txt");

        assertEquals("PARSE_FAILED", body.get("code").asText());
        assertTrue(body.get("data").isNull());
        assertTrue(body.get("message").asText().contains("line template"));
    }

    @Test
    void unparseableTimestampIsSkippedAsOther() throws Exception {
        byte[] content = txt("""
                2024-13-99 99:99:99 张三
                this header matched the template shape but the date is not real

                2024-01-01 10:00:00 李四
                a genuinely valid message
                """);

        JsonNode body = postParseChat("chat.txt", content, "txt");

        assertEquals("OK", body.get("code").asText());
        JsonNode data = body.get("data");
        JsonNode messages = data.get("sessions").get(0).get("messages");
        assertEquals(1, messages.size());
        assertEquals("李四", messages.get(0).get("sender").asText());
        assertEquals(1, data.get("skipped").get("other").asInt());
    }

    @Test
    void aLongMultiLineBodyDoesNotTripTheUnmatchedRatio() throws Exception {
        // The failure line exists to catch the wrong template, not a normal long message: only lines
        // arriving before any message has started count toward it.
        StringBuilder builder = new StringBuilder("2024-01-01 10:00:00 张三\n");
        for (int i = 0; i < 50; i++) {
            builder.append("continuation line ").append(i).append('\n');
        }

        JsonNode body = postParseChat("chat.txt", txt(builder.toString()), "txt");

        assertEquals("OK", body.get("code").asText());
        assertEquals(1, body.get("data").get("sessions").get(0).get("messages").size());
    }
}
