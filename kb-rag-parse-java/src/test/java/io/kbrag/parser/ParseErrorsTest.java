package io.kbrag.parser;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Failure normalization at the request boundary.
 *
 * @author owlzhangfq@gmail.com
 */
class ParseErrorsTest extends ParseEndpointTestBase {

    @Test
    void parseRejectsUnsupportedFileExt() throws Exception {
        JsonNode body = postParse("sample.exe", "whatever".getBytes(StandardCharsets.UTF_8), "exe");

        assertEquals("PARSE_FAILED", body.get("code").asText());
        assertTrue(body.get("data").isNull());
        assertTrue(body.get("message").asText().toLowerCase().contains("unsupported"));
    }

    @Test
    void parseRejectsCorruptPdfWithoutLeakingAStackTrace() throws Exception {
        JsonNode body = postParse("broken.pdf", "not a pdf at all".getBytes(StandardCharsets.UTF_8), "pdf");

        assertEquals("PARSE_FAILED", body.get("code").asText());
        assertTrue(body.get("data").isNull());
        assertTrue(body.get("message").asText().contains("pdf"));
        assertTrue(body.get("request_id").asText().length() > 0);
    }

    @Test
    void chatRejectsUnsupportedFileExt() throws Exception {
        JsonNode body = postParseChat("chat.pdf", "irrelevant".getBytes(StandardCharsets.UTF_8), "pdf");

        assertEquals("PARSE_FAILED", body.get("code").asText());
        assertTrue(body.get("message").asText().contains("unsupported chat file_ext"));
    }
}
