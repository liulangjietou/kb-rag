package io.kbrag.parser.ocr;

/**
 * {@code OCR_ENGINE=none}, the default: scanned pages are rendered to PNG and left for kb-rag-server's
 * VLM, with no text backfilled and no {@code ocr_source} set.
 *
 * @author owlzhangfq@gmail.com
 */
public class NoOpOcrEngine implements OcrEngine {

    @Override
    public String recognize(byte[] pngBytes, int pageNo) {
        return null;
    }

    @Override
    public String ocrSource() {
        return null;
    }
}
