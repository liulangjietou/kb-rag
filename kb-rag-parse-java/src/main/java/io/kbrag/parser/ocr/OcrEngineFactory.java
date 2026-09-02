package io.kbrag.parser.ocr;

import io.kbrag.parser.config.ParserConstants;
import io.kbrag.parser.config.ParserProperties;
import io.kbrag.parser.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Resolves the configured {@link OcrEngine} and enforces the startup gate for it
 * (M8-CONTRACTS.md §0.4).
 *
 * <p>The gate is the one place in this service where a configuration mistake is a hard startup
 * failure rather than a graceful default. That asymmetry is deliberate: every other setting degrades
 * to a documented value because a typo should not take a service down, but an operator who set
 * {@code OCR_ENGINE} did so expecting scanned pages to come back with text. Falling back to "none"
 * would honour the typo by silently indexing nothing, and the symptom would surface hours later as
 * missing content rather than as a failed boot.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OcrEngineFactory {

    private final ParserProperties properties;

    private volatile OcrEngine engine;

    /**
     * @return the engine for the current configuration; a shared instance, safe to call per page
     */
    public OcrEngine getOcrEngine() {
        OcrEngine current = engine;
        if (current == null) {
            synchronized (this) {
                current = engine;
                if (current == null) {
                    current = createEngine();
                    engine = current;
                }
            }
        }
        return current;
    }

    private OcrEngine createEngine() {
        if (isTesseractConfigured()) {
            return new TesseractOcrEngine(properties);
        }
        return new NoOpOcrEngine();
    }

    /**
     * Fast-fails startup when a local OCR engine is configured but its optional dependency is missing.
     *
     * @throws IllegalStateException when the configured engine cannot be loaded
     */
    public void ensureOcrEngineReady() {
        String configured = normalizedEngineName();
        if (ParserConstants.OCR_ENGINE_NONE.equals(configured)) {
            return;
        }
        if (!ParserConstants.OCR_ENGINE_TESSERACT.equals(configured)) {
            log.error("ocr engine unrecognized at startup, errorCode={}, ocrEngine={}",
                    ErrorCode.PARSE_FAILED, configured);
            throw new IllegalStateException("unsupported OCR_ENGINE '" + configured + "'; supported: "
                    + ParserConstants.OCR_ENGINE_NONE + ", " + ParserConstants.OCR_ENGINE_TESSERACT);
        }
        if (!TesseractOcrEngine.isTesseractInstalled()) {
            log.error("ocr engine unavailable at startup, errorCode={}, ocrEngine={}",
                    ErrorCode.PARSE_FAILED, configured);
            throw new IllegalStateException(
                    "OCR_ENGINE=tesseract is configured but tess4j is not on the classpath; "
                            + "build with 'mvn -Pocr package', or set OCR_ENGINE=none");
        }
        log.info("ocr engine ready, ocrEngine={}, language={}", configured, properties.getOcrLanguage());
    }

    private boolean isTesseractConfigured() {
        return ParserConstants.OCR_ENGINE_TESSERACT.equals(normalizedEngineName())
                && TesseractOcrEngine.isTesseractInstalled();
    }

    private String normalizedEngineName() {
        String raw = properties.getOcrEngine();
        return raw == null ? ParserConstants.OCR_ENGINE_NONE : raw.trim().toLowerCase(Locale.ROOT);
    }
}
