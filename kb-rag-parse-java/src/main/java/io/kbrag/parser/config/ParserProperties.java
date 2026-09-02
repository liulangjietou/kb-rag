package io.kbrag.parser.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Operator-tunable runtime configuration, one property per documented environment variable.
 *
 * <p>Every field is bound in {@code application.yml} from the same environment variable name the Python
 * service documents ({@code SCANNED_PAGE_TEXT_THRESHOLD}, {@code MAX_IMAGES_PER_DOC}, ...), so a
 * deployment can swap one implementation for the other without rewriting its environment.
 *
 * <p>A malformed value degrades to the documented default rather than crashing startup, matching
 * {@code app/config.py::_read_int_env}: fast-fail belongs at the request boundary, where a caller can
 * be told what it did wrong, not at boot, where a stray character in an env var would take the service
 * down. The one exception is {@code OCR_ENGINE}, whose misconfiguration <i>is</i> a startup fast-fail
 * (M8-CONTRACTS.md §0.4) - see {@code ocr/OcrEngineFactory}.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "kb.parser")
public class ParserProperties {

    /**
     * A pdf page whose extracted text (whitespace-stripped) is shorter than this has no usable text
     * layer ("scanned") and is rendered to PNG instead (M3-CONTRACTS.md §2.1).
     */
    private int scannedPageTextThreshold = 20;

    /**
     * A pdf page whose text layer exists but decodes mostly to unrecognizable code points - the
     * signature of an embedded subset font with a missing/broken ToUnicode CMap, where CJK content
     * surfaces as glyph soup - is treated as garbled once fewer than this percentage of its
     * non-whitespace characters fall in recognizable Unicode ranges, and falls back to the
     * scanned-page path rather than indexing the soup.
     */
    private int garbledPageValidCharRatioPct = 50;

    /** Per-document image count cap; exceeding it skips that image and records a warning. */
    private int maxImagesPerDoc = 100;

    /** Per-image byte size cap; exceeding it skips that image and records a warning. */
    private long maxImageBytes = 10L * 1024 * 1024;

    /**
     * The service's real parse concurrency ceiling.
     *
     * <p>However many documents kb-rag-server submits at once, only this many are parsed in parallel
     * and the rest queue: raising the caller's INDEX_CONCURRENCY past this value does not make anything
     * faster, it only moves the queue upstream. These threads do real CPU work (pdf text extraction,
     * page rendering, image decoding), so keep the value under the core count and leave room for
     * whatever else runs alongside.
     */
    private int maxWorkers = 4;

    /** {@code none} or {@code tesseract} (M8-CONTRACTS.md §0.4); see ParserConstants. */
    private String ocrEngine = ParserConstants.OCR_ENGINE_NONE;

    /** Per-page OCR wall-clock budget in seconds; on timeout the page is skipped and counted. */
    private int ocrTimeoutSeconds = 30;

    /** Tesseract language(s), passed straight through to the engine (e.g. {@code chi_sim+eng}). */
    private String ocrLanguage = "chi_sim+eng";

    /** Tesseract {@code tessdata} directory; blank lets the engine use its own default lookup. */
    private String ocrDataPath = "";

    /** True when a local OCR fallback is configured at all. */
    public boolean isLocalOcrEnabled() {
        return !ParserConstants.OCR_ENGINE_NONE.equalsIgnoreCase(ocrEngine);
    }
}
