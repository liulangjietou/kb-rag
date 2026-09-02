package io.kbrag.parser;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pdf baseline: a page with a real text layer.
 *
 * @author owlzhangfq@gmail.com
 */
class ParsePdfTest extends ParseEndpointTestBase {

    @Test
    void parsePdfReturnsExpectedStructure() throws Exception {
        JsonNode body = postParse("sample.pdf",
                ParserTestSupport.pdfBytes(ParserTestSupport.NORMAL_PAGE_TEXT), "pdf");

        assertEquals("OK", body.get("code").asText());
        assertFalse(body.get("request_id").asText().isEmpty());
        JsonNode data = body.get("data");
        assertTrue(data.get("markdown").asText().contains("Hello kb-rag PDF"));
        assertEquals(1, data.get("pages").size());
        JsonNode page = data.get("pages").get(0);
        assertEquals(1, page.get("page_no").asInt());
        assertTrue(page.get("text").asText().contains("Hello kb-rag PDF"));
        // A page with a long enough text layer is never scanned and has no images.
        assertFalse(page.get("scanned").asBoolean());
        assertTrue(page.get("ocr_source").isNull());
        assertTrue(data.get("images").isEmpty());
        assertTrue(data.get("warnings").isEmpty());
    }

    @Test
    void parsePdfNumbersEveryPage() throws Exception {
        JsonNode body = postParse("multi.pdf", ParserTestSupport.multiPagePdfBytes(3), "pdf");

        JsonNode pages = body.get("data").get("pages");
        assertEquals(3, pages.size());
        for (int i = 0; i < pages.size(); i++) {
            assertEquals(i + 1, pages.get(i).get("page_no").asInt());
            assertTrue(pages.get(i).get("text").asText().contains("Page " + (i + 1) + " body text"));
        }
    }
}
