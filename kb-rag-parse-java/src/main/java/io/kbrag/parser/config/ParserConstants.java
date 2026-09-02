package io.kbrag.parser.config;

/**
 * Constants fixed by contract, and therefore deliberately not configurable.
 *
 * <p>Everything an operator may tune lives in {@link ParserProperties} instead. The split mirrors the
 * Python service's {@code app/config.py}, where the same values are module constants while only the
 * documented environment variables are read through a helper: a value nobody may change is a poor
 * candidate for a property, because a property invites the belief that changing it is supported.
 *
 * @author owlzhangfq@gmail.com
 */
public final class ParserConstants {

    /** Service identity, also the port kb-rag-server dials (M1-CONTRACTS.md §0). */
    public static final String SERVICE_NAME = "kb-rag-parse-java";

    // --- Upload / parse limits (requirement §4.2, M1-CONTRACTS.md §6) ---

    /** Single uploaded file size hard limit: 100MB. */
    public static final long MAX_FILE_SIZE_BYTES = 100L * 1024 * 1024;

    /** Total uncompressed size across all zip entries of a docx/xlsx package: 500MB. */
    public static final long MAX_ZIP_UNCOMPRESSED_TOTAL_BYTES = 500L * 1024 * 1024;

    /** Number of entries inside a docx/xlsx zip package. */
    public static final int MAX_ZIP_ENTRY_COUNT = 2000;

    /** Hard timeout for a single parse invocation, in seconds. */
    public static final int PARSE_TIMEOUT_SECONDS = 300;

    // --- M3 multimodal parsing (M3-CONTRACTS.md §2.1) ---

    /**
     * DPI used to render a scanned page to PNG. Fixed by the contract, not configurable - only the
     * threshold that decides <i>whether</i> a page is scanned is (see
     * {@link ParserProperties#getScannedPageTextThreshold()}).
     */
    public static final int SCANNED_PAGE_RENDER_DPI = 150;

    /**
     * Markdown placeholder token for an extracted/rendered image. Must occupy its own line so
     * kb-rag-server can locate and replace it precisely.
     */
    public static final String IMAGE_PLACEHOLDER_FORMAT = "[[IMAGE:%s]]";

    // --- M3 chat log parsing (M3-CONTRACTS.md §2.2) ---

    /** Built-in mapping profile for csv/xlsx when the caller passes no mapping_profile. */
    public static final String DEFAULT_CHAT_MAPPING_PROFILE = "memotrace";

    /**
     * A TXT export whose configured line templates match too few lines is almost certainly the wrong
     * format/profile - fail fast with an actionable error instead of silently emitting a near-empty
     * session (M8-CONTRACTS.md §0.1).
     */
    public static final double TXT_UNMATCHED_LINE_RATIO_FAIL_THRESHOLD = 0.30;

    // --- M8 local OCR fallback (M8-CONTRACTS.md §0.4) ---

    public static final String OCR_ENGINE_NONE = "none";

    /**
     * Tesseract stands in for the Python service's PaddleOCR: no PaddleOCR binding exists for the JVM,
     * and this tier only has to answer "can this service read a scanned page without a model key".
     * kb-rag-server keys off {@code ocr_source} being present, not off its value
     * (ParsedDocument.Page#isOcrBackfilled), so the marker below stays contract-compatible.
     */
    public static final String OCR_ENGINE_TESSERACT = "tesseract";

    /** Marks a page as already OCR'd by this service, so kb-rag-server skips its own VLM pass. */
    public static final String OCR_SOURCE_TESSERACT = "tesseract";

    private ParserConstants() {
    }
}
