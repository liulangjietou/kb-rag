package io.kbrag.parser;

import com.fasterxml.jackson.databind.JsonNode;
import io.kbrag.parser.config.ParserConstants;
import io.kbrag.parser.config.ParserProperties;
import io.kbrag.parser.ocr.NoOpOcrEngine;
import io.kbrag.parser.ocr.OcrEngine;
import io.kbrag.parser.ocr.OcrEngineFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The local OCR fallback switch (M8-CONTRACTS.md §0.4).
 *
 * <p>Real inference is not exercised here: tess4j sits behind the optional {@code ocr} Maven profile
 * and needs a native Tesseract install, so a default build has neither. What is exercised is
 * everything the contract actually pins - the tri-state switch, the startup gate, and the fact that
 * the default behaviour is byte-for-byte what it was before OCR existed.
 *
 * @author owlzhangfq@gmail.com
 */
class ParsePdfOcrTest extends ParseEndpointTestBase {

    @Test
    void ocrEngineNoneIsTheDefaultAndScannedPageBehaviourIsUnchanged() throws Exception {
        assertEquals(ParserConstants.OCR_ENGINE_NONE, properties.getOcrEngine());

        JsonNode body = postParse("scanned.pdf", ParserTestSupport.scannedPdfBytes(1), "pdf");

        assertEquals("OK", body.get("code").asText());
        JsonNode page = body.get("data").get("pages").get(0);
        assertTrue(page.get("scanned").asBoolean());
        assertEquals("", page.get("text").asText());
        assertTrue(page.get("ocr_source").isNull());
    }

    @Test
    void factoryReturnsTheNoOpEngineWhenOcrIsOff() {
        ParserProperties config = new ParserProperties();
        config.setOcrEngine(ParserConstants.OCR_ENGINE_NONE);

        assertInstanceOf(NoOpOcrEngine.class, new OcrEngineFactory(config).getOcrEngine());
    }

    @Test
    void theNoOpEngineNeverBackfillsAnOcrSource() {
        OcrEngine engine = new NoOpOcrEngine();

        assertEquals(null, engine.recognize(new byte[]{1, 2, 3}, 1));
        assertEquals(null, engine.ocrSource());
    }

    @Test
    void startupGateIsANoOpForOcrEngineNone() {
        ParserProperties config = new ParserProperties();
        config.setOcrEngine(ParserConstants.OCR_ENGINE_NONE);

        assertDoesNotThrow(() -> new OcrEngineFactory(config).ensureOcrEngineReady());
    }

    @Test
    void startupGateRejectsAnUnrecognizedEngineName() {
        // A typo must not degrade to "none": an operator who set OCR_ENGINE expects scanned pages to
        // come back with text, and silently indexing nothing would surface hours later as missing
        // content rather than as a failed boot.
        ParserProperties config = new ParserProperties();
        config.setOcrEngine("paddle");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new OcrEngineFactory(config).ensureOcrEngineReady());
        assertTrue(ex.getMessage().contains("unsupported OCR_ENGINE"));
    }

    @Test
    void startupGateFastFailsWhenTesseractIsConfiguredButNotOnTheClasspath() {
        ParserProperties config = new ParserProperties();
        config.setOcrEngine(ParserConstants.OCR_ENGINE_TESSERACT);

        if (io.kbrag.parser.ocr.TesseractOcrEngine.isTesseractInstalled()) {
            // Built with -Pocr: the gate must pass instead, which is the other half of the contract.
            assertDoesNotThrow(() -> new OcrEngineFactory(config).ensureOcrEngineReady());
            return;
        }
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new OcrEngineFactory(config).ensureOcrEngineReady());
        assertTrue(ex.getMessage().contains("tess4j"));
    }
}
