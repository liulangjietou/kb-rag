package io.kbrag.parser;

import com.fasterxml.jackson.databind.JsonNode;
import io.kbrag.parser.config.ParserConstants;
import io.kbrag.parser.ocr.OcrEngine;
import io.kbrag.parser.ocr.OcrEngineFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a working local OCR engine changes about a scanned page (M8-CONTRACTS.md §0.4), with the engine
 * stubbed so the behaviour under test is the parser's, not Tesseract's.
 *
 * @author owlzhangfq@gmail.com
 */
@Import(ParsePdfOcrBackfillTest.StubOcrConfiguration.class)
class ParsePdfOcrBackfillTest extends ParseEndpointTestBase {

    static final String STUB_OCR_SOURCE = "tesseract";

    @TestConfiguration
    static class StubOcrConfiguration {

        @Bean
        @Primary
        OcrEngineFactory stubOcrEngineFactory(io.kbrag.parser.config.ParserProperties properties) {
            return new OcrEngineFactory(properties) {
                @Override
                public OcrEngine getOcrEngine() {
                    return new OcrEngine() {
                        @Override
                        public String recognize(byte[] pngBytes, int pageNo) {
                            assertTrue(pngBytes != null && pngBytes.length > 0,
                                    "the engine must receive a real render");
                            return "ocr text of page " + pageNo;
                        }

                        @Override
                        public String ocrSource() {
                            return STUB_OCR_SOURCE;
                        }
                    };
                }
            };
        }
    }

    @BeforeEach
    void enableLocalOcr() {
        properties.setOcrEngine(ParserConstants.OCR_ENGINE_TESSERACT);
    }

    @Test
    void scannedPageTextIsBackfilledAndMarked() throws Exception {
        JsonNode body = postParse("scanned3.pdf", ParserTestSupport.scannedPdfBytes(3), "pdf");

        assertEquals("OK", body.get("code").asText());
        for (JsonNode page : body.get("data").get("pages")) {
            assertEquals(STUB_OCR_SOURCE, page.get("ocr_source").asText());
            assertEquals("ocr text of page " + page.get("page_no").asInt(), page.get("text").asText());
        }
    }

    @Test
    void pagesPastTheImageCapAreStillReadEvenThoughTheirRenderIsNotReturned() throws Exception {
        // The cap bounds the images the response carries, not this service's ability to read a page.
        properties.setMaxImagesPerDoc(1);

        JsonNode body = postParse("scanned3.pdf", ParserTestSupport.scannedPdfBytes(3), "pdf");

        JsonNode data = body.get("data");
        assertEquals(1, data.get("images").size(), "the image cap still applies to the response");
        for (JsonNode page : data.get("pages")) {
            assertEquals(STUB_OCR_SOURCE, page.get("ocr_source").asText());
        }
    }
}
