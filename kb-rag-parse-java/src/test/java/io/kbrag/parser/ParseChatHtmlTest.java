package io.kbrag.parser;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The HTML DOM adapter (M8-CONTRACTS.md §0.2): the built-in liuhen template, the image placeholder,
 * voice/video skipping, script/style stripping, custom selectors over the wire, and the
 * selector-matched-nothing fast-fail.
 *
 * @author owlzhangfq@gmail.com
 */
class ParseChatHtmlTest extends ParseEndpointTestBase {

    private static byte[] html(String body) {
        return ("<html><body>" + body + "</body></html>").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void liuhenTemplatePositive() throws Exception {
        byte[] content = html("""
                <div class="message">
                  <span class="sender">张三</span>
                  <span class="time">2024-01-01 10:00:00</span>
                  <div class="content">你好，最近怎么样？</div>
                </div>
                <div class="message">
                  <span class="sender">李四</span>
                  <span class="time">2024-01-01 10:05:00</span>
                  <div class="content">挺好的，你呢？</div>
                </div>
                """);

        JsonNode body = postParseChat("chat.html", content, "html");

        assertEquals("OK", body.get("code").asText());
        JsonNode data = body.get("data");
        assertEquals(1, data.get("sessions").size());

        JsonNode first = data.get("sessions").get(0).get("messages").get(0);
        assertEquals("张三", first.get("sender").asText());
        assertEquals("text", first.get("msg_type").asText());
        assertEquals("你好，最近怎么样？", first.get("content").asText());
        // Exact value is timezone-dependent; it only has to parse.
        assertTrue(first.get("send_time").asLong() > 0);

        assertEquals(0, data.get("skipped").get("other").asInt());
    }

    @Test
    void imageMessageBecomesAPlaceholderAndIsNeverFetched() throws Exception {
        byte[] content = html("""
                <div class="message">
                  <span class="sender">张三</span>
                  <span class="time">2024-01-01 10:00:00</span>
                  <div class="content"><img src="http://example.com/should-not-be-fetched.png"/></div>
                </div>
                """);

        JsonNode body = postParseChat("chat.html", content, "html");

        assertEquals("OK", body.get("code").asText());
        JsonNode message = body.get("data").get("sessions").get(0).get("messages").get(0);
        assertEquals("image", message.get("msg_type").asText());
        assertEquals("[IMAGE]", message.get("content").asText());
        // The src is never read, so it can never reach the output either.
        assertFalse(message.get("content").asText().contains("example.com"));
    }

    @Test
    void voiceAndVideoMessagesAreSkippedButCounted() throws Exception {
        byte[] content = html("""
                <div class="message">
                  <span class="sender">A</span>
                  <span class="time">2024-01-01 10:00:00</span>
                  <div class="content">text message</div>
                </div>
                <div class="message">
                  <span class="sender">B</span>
                  <span class="time">2024-01-01 10:01:00</span>
                  <div class="content"><audio src="voice.amr"></audio></div>
                </div>
                <div class="message">
                  <span class="sender">C</span>
                  <span class="time">2024-01-01 10:02:00</span>
                  <div class="content"><video src="clip.mp4"></video></div>
                </div>
                """);

        JsonNode body = postParseChat("chat.html", content, "html");

        JsonNode data = body.get("data");
        JsonNode messages = data.get("sessions").get(0).get("messages");
        assertEquals(1, messages.size());
        assertEquals("A", messages.get(0).get("sender").asText());
        assertEquals(1, data.get("skipped").get("voice").asInt());
        assertEquals(1, data.get("skipped").get("video").asInt());
        assertEquals(0, data.get("skipped").get("other").asInt());
    }

    @Test
    void scriptAndStyleContentIsStripped() throws Exception {
        byte[] content = html("""
                <script>alert('should never be executed or surfaced as text');</script>
                <style>.message { color: red; }</style>
                <div class="message">
                  <span class="sender">张三</span>
                  <span class="time">2024-01-01 10:00:00</span>
                  <div class="content">hello<script>evil()</script></div>
                </div>
                """);

        JsonNode body = postParseChat("chat.html", content, "html");

        JsonNode message = body.get("data").get("sessions").get(0).get("messages").get(0);
        assertEquals("hello", message.get("content").asText());
        assertFalse(message.get("content").asText().contains("alert"));
        assertFalse(message.get("content").asText().contains("evil"));
    }

    @Test
    void customSelectorsViaProfileYaml() throws Exception {
        byte[] content = html("""
                <li class="chat-row">
                  <b class="who">alice</b>
                  <i class="at">2024-01-01 10:00:00</i>
                  <p class="msg">hi there</p>
                </li>
                """);
        String profileYaml = """
                html:
                  message: li.chat-row
                  sender: b.who
                  time: i.at
                  content: p.msg
                """;

        JsonNode body = postParseChat("chat.html", content, "html", null, profileYaml);

        assertEquals("OK", body.get("code").asText());
        JsonNode message = body.get("data").get("sessions").get(0).get("messages").get(0);
        assertEquals("alice", message.get("sender").asText());
        assertEquals("hi there", message.get("content").asText());
    }

    @Test
    void nonBreakingSpacesAroundFieldsAreStripped() throws Exception {
        // Exported chat HTML pads its cells with &nbsp; constantly; Java's String.strip() would leave
        // those invisible characters on the sender name and the message body.
        byte[] content = html("""
                <div class="message">
                  <span class="sender">&nbsp;\u5f20\u4e09&nbsp;</span>
                  <span class="time">&nbsp;2024-01-01 10:00:00&nbsp;</span>
                  <div class="content">&nbsp;hello&nbsp;</div>
                </div>
                """);

        JsonNode body = postParseChat("chat.html", content, "html");

        assertEquals("OK", body.get("code").asText());
        JsonNode message = body.get("data").get("sessions").get(0).get("messages").get(0);
        assertEquals("\u5f20\u4e09", message.get("sender").asText());
        assertEquals("hello", message.get("content").asText());
        // The timestamp too: a &nbsp;-padded value must still parse rather than count as skipped.
        assertTrue(message.get("send_time").asLong() > 0);
        assertEquals(0, body.get("data").get("skipped").get("other").asInt());
    }

    @Test
    void messageSelectorMatchingNothingFails() throws Exception {
        JsonNode body = postParseChat("chat.html",
                html("<div class='not-a-message'>irrelevant</div>"), "html");

        assertEquals("PARSE_FAILED", body.get("code").asText());
        assertTrue(body.get("data").isNull());
        assertTrue(body.get("message").asText().contains("selector"));
    }

    @Test
    void missingRequiredSelectorFails() throws Exception {
        JsonNode body = postParseChat("chat.html",
                html("<div class='message'>x</div>"), "html", null, "html:\n  message: div.message\n");

        assertEquals("PARSE_FAILED", body.get("code").asText());
        assertTrue(body.get("message").asText().contains("missing required selector"));
    }
}
