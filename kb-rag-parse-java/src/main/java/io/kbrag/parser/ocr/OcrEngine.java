package io.kbrag.parser.ocr;

/**
 * Strategy interface for the scanned-page OCR fallback (M8-CONTRACTS.md §0.4).
 *
 * <p>Three tiers own the question "who reads a scanned page's PNG render": kb-rag-server's VLM (has a
 * model key, the status quo) -> this service's optional local engine (offline, zero-key) -> skip and
 * degrade. Only the middle tier lives here, and it is invoked once per scanned page, never for a page
 * that already has a usable text layer.
 *
 * @author owlzhangfq@gmail.com
 */
public interface OcrEngine {

    /**
     * Reads text out of one rendered page.
     *
     * @param pngBytes the page render
     * @param pageNo   1-based page number, for diagnostics
     * @return the recognized text, or null when this page could not be read - not installed, timed
     *         out, or the engine threw. A null is always a per-page skip that falls back to the
     *         pre-OCR behaviour for that one page, never a whole-document failure.
     */
    String recognize(byte[] pngBytes, int pageNo);

    /**
     * @return the value to report in {@code pages[].ocr_source} when {@link #recognize} produced text,
     *         or null for an engine that never produces any
     */
    String ocrSource();
}
