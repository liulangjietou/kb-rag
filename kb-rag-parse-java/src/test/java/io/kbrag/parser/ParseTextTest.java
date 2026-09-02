package io.kbrag.parser;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain-text formats: txt, md and sql pass through untransformed.
 *
 * @author owlzhangfq@gmail.com
 */
class ParseTextTest extends ParseEndpointTestBase {

    @Test
    void parseTxtReturnsExpectedStructure() throws Exception {
        JsonNode body = postParse("sample.txt",
                "Hello kb-rag TXT\nsecond line".getBytes(StandardCharsets.UTF_8), "txt");

        assertEquals("OK", body.get("code").asText());
        JsonNode data = body.get("data");
        assertEquals("Hello kb-rag TXT\nsecond line", data.get("markdown").asText());
        assertEquals(1, data.get("pages").size());
        assertEquals(1, data.get("pages").get(0).get("page_no").asInt());
        assertTrue(data.get("images").isEmpty());
    }

    @Test
    void parseMdReturnsExpectedStructure() throws Exception {
        JsonNode body = postParse("sample.md",
                "# Heading\n\nHello kb-rag MD".getBytes(StandardCharsets.UTF_8), "md");

        assertEquals("OK", body.get("code").asText());
        String markdown = body.get("data").get("markdown").asText();
        assertTrue(markdown.contains("# Heading"));
        assertTrue(markdown.contains("Hello kb-rag MD"));
        assertTrue(body.get("data").get("images").isEmpty());
    }

    @Test
    void parseSqlReturnsExpectedStructure() throws Exception {
        String sql = "SELECT id, name FROM t_kb\nWHERE status = 'READY';";
        JsonNode body = postParse("schema.sql", sql.getBytes(StandardCharsets.UTF_8), "sql");

        assertEquals("OK", body.get("code").asText());
        JsonNode data = body.get("data");
        assertEquals(sql, data.get("markdown").asText());
        assertEquals(1, data.get("pages").size());
        assertTrue(data.get("images").isEmpty());
    }

    @Test
    void parseTxtDecodesGbk() throws Exception {
        byte[] gbk = "中文编码测试".getBytes(java.nio.charset.Charset.forName("GBK"));

        JsonNode body = postParse("gbk.txt", gbk, "txt");

        assertEquals("OK", body.get("code").asText());
        assertEquals("中文编码测试", body.get("data").get("markdown").asText());
    }
}
