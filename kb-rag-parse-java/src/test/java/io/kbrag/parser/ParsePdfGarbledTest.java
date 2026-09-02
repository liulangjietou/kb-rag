package io.kbrag.parser;

import com.fasterxml.jackson.databind.JsonNode;
import io.kbrag.parser.config.ParserProperties;
import io.kbrag.parser.ocr.OcrEngineFactory;
import io.kbrag.parser.parser.PdfParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The garbled text-layer fallback, end to end (M3-CONTRACTS.md §2.1 扫描页判定 extension).
 *
 * <p>A pdf with a genuinely broken ToUnicode CMap cannot be fabricated with PDFBox's own writer, which
 * always emits a valid one, so the broken extraction result is substituted at the parser's extraction
 * seam - the same thing the Python suite does by patching {@code get_text()}.
 *
 * @author owlzhangfq@gmail.com
 */
@Import(ParsePdfGarbledTest.GarbledTextConfiguration.class)
class ParsePdfGarbledTest extends ParseEndpointTestBase {

    @TestConfiguration
    static class GarbledTextConfiguration {

        @Bean
        @Primary
        PdfParser garbledPdfParser(ParserProperties properties, OcrEngineFactory ocrEngineFactory) {
            return new PdfParser(properties, ocrEngineFactory) {
                @Override
                protected String extractPageText(PDFTextStripper stripper, PDDocument document, int pageNo) {
                    return GarbledTextDetectorTest.GARBLED_TEXT;
                }
            };
        }
    }

    @Test
    void garbledPdfPageFallsBackToPageRender() throws Exception {
        JsonNode body = postParse("garbled.pdf",
                ParserTestSupport.pdfBytes(ParserTestSupport.NORMAL_PAGE_TEXT), "pdf");

        assertEquals("OK", body.get("code").asText());
        JsonNode data = body.get("data");
        JsonNode page = data.get("pages").get(0);
        assertTrue(page.get("scanned").asBoolean());
        assertEquals("", page.get("text").asText(), "glyph soup never reaches chunking");
        assertEquals(1, data.get("images").size());
        assertEquals("page_render", data.get("images").get(0).get("kind").asText());

        boolean warned = false;
        for (JsonNode warning : data.get("warnings")) {
            warned |= warning.asText().contains("garbled");
        }
        assertTrue(warned, "the degradation must be visible in warnings[]");
    }
}
