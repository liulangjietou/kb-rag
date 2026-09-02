package io.kbrag.parser;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The general HTML page parser (M12-CONTRACTS.md §2).
 *
 * @author owlzhangfq@gmail.com
 */
class ParseHtmlTest extends ParseEndpointTestBase {

    private static final String SAMPLE_HTML = """
            <!DOCTYPE html>
            <html>
            <head>
              <title>kb-rag 指南</title>
              <meta charset="utf-8">
              <style>body { color: red; }</style>
              <script>console.log("tracking");</script>
            </head>
            <body>
              <h1>快速开始</h1>
              <p>第一段，包含一个 <a href="https://example.com/link">链接文本</a> 与 <b>加粗</b>。</p>
              <h2>安装步骤</h2>
              <ul>
                <li>第一步</li>
                <li>第二步</li>
              </ul>
              <script>alert("in body");</script>
              <noscript>请开启 JS</noscript>
            </body>
            </html>
            """;

    private JsonNode parseSample() throws Exception {
        return postParse("guide.html", SAMPLE_HTML.getBytes(StandardCharsets.UTF_8), "html");
    }

    @Test
    void parseHtmlReturnsExpectedStructure() throws Exception {
        JsonNode body = parseSample();

        assertEquals("OK", body.get("code").asText());
        JsonNode data = body.get("data");
        String markdown = data.get("markdown").asText();
        // The title becomes the top-level heading; tag headings keep their own levels.
        assertTrue(markdown.startsWith("# kb-rag 指南"), markdown);
        assertTrue(markdown.contains("# 快速开始"));
        assertTrue(markdown.contains("## 安装步骤"));
        assertEquals(1, data.get("pages").size());
        assertEquals(1, data.get("pages").get(0).get("page_no").asInt());
        assertTrue(data.get("images").isEmpty());
    }

    @Test
    void parseHtmlStripsInvisibleContent() throws Exception {
        String markdown = parseSample().get("data").get("markdown").asText();

        // script/style/noscript bodies are invisible on the page, so they must not leak into the
        // retrieval corpus.
        assertFalse(markdown.contains("console.log"));
        assertFalse(markdown.contains("color: red"));
        assertFalse(markdown.contains("alert("));
        assertFalse(markdown.contains("请开启 JS"));
    }

    @Test
    void parseHtmlKeepsAnchorTextAndDropsHref() throws Exception {
        String markdown = parseSample().get("data").get("markdown").asText();

        assertTrue(markdown.contains("链接文本"));
        assertFalse(markdown.contains("https://example.com/link"));
    }

    @Test
    void parseHtmlBlocksBecomeSeparateLines() throws Exception {
        JsonNode body = postParse("blocks.html",
                "<div>alpha</div><div>beta</div>".getBytes(StandardCharsets.UTF_8), "html");

        String markdown = body.get("data").get("markdown").asText();
        // Adjacent blocks must not run together into one word.
        assertFalse(markdown.contains("alphabeta"));
        assertTrue(markdown.contains("alpha"));
        assertTrue(markdown.contains("beta"));
    }

    @Test
    void parseHtmExtensionIsRegistered() throws Exception {
        JsonNode body = postParse("legacy.htm",
                "<p>htm works</p>".getBytes(StandardCharsets.UTF_8), "htm");

        assertEquals("OK", body.get("code").asText());
        assertTrue(body.get("data").get("markdown").asText().contains("htm works"));
    }

    @Test
    void parseHtmlGbkEncoded() throws Exception {
        byte[] content = "<html><body><p>中文编码测试</p></body></html>"
                .getBytes(Charset.forName("GBK"));

        JsonNode body = postParse("gbk.html", content, "html");

        assertEquals("OK", body.get("code").asText());
        assertTrue(body.get("data").get("markdown").asText().contains("中文编码测试"));
    }

    @Test
    void nonBreakingSpacesAreCollapsedLikeOrdinaryWhitespace() throws Exception {
        // &nbsp; is the single most common invisible character in real pages, and Java's own \s and
        // String.strip() both ignore it - left alone it would survive into the corpus here while the
        // Python implementation strips it.
        byte[] content = "<html><body><h1>&nbsp;\u6807\u9898&nbsp;</h1><p>a&nbsp;&nbsp;b</p></body></html>"
                .getBytes(StandardCharsets.UTF_8);

        JsonNode body = postParse("nbsp.html", content, "html");

        String markdown = body.get("data").get("markdown").asText();
        assertTrue(markdown.contains("# \u6807\u9898"), markdown);
        assertFalse(markdown.contains("\u00a0"), "no non-breaking space may survive into the corpus");
        assertTrue(markdown.contains("a b"), markdown);
    }

    @Test
    void parseHtmlMalformedMarkupIsTolerated() throws Exception {
        // Unclosed tags and stray closers still yield the visible text instead of an error.
        JsonNode body = postParse("broken.html",
                "<p>unclosed <b>bold</p></i><div>tail".getBytes(StandardCharsets.UTF_8), "html");

        assertEquals("OK", body.get("code").asText());
        String markdown = body.get("data").get("markdown").asText();
        assertTrue(markdown.contains("unclosed"));
        assertTrue(markdown.contains("bold"));
        assertTrue(markdown.contains("tail"));
    }
}
