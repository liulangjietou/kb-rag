package io.kbrag.parser.ocr;

import io.kbrag.parser.config.ParserConstants;
import io.kbrag.parser.config.ParserProperties;
import io.kbrag.parser.support.Whitespace;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Local OCR fallback backed by Tesseract through tess4j (M8-CONTRACTS.md §0.4).
 *
 * <p>tess4j is an optional dependency, behind the {@code ocr} Maven profile and absent from the
 * default image - the counterpart of the Python service's {@code requirements-ocr.txt}, and for the
 * same reason: a service whose entire posture is "no reason to phone home" has no business shipping a
 * model runtime nobody asked for. It is therefore invoked reflectively, so this class compiles and
 * loads whether or not the jar is on the classpath, and
 * {@link OcrEngineFactory#ensureOcrEngineReady()} fast-fails at startup when the engine is configured
 * but unavailable, rather than surfacing a confusing failure inside the first request that happens to
 * hit a scanned page.
 *
 * <p>The engine handle is per-thread because a Tesseract instance is not safe to share, and building
 * one is expensive enough that a fresh instance per page would dominate the OCR cost. The OCR thread
 * pool is bounded, so the number of live handles is bounded with it.
 *
 * <p><b>Why not PaddleOCR.</b> The Python service pins {@code ch_PP-OCRv4} and reports
 * {@code ocr_source="paddle"}; PaddleOCR has no JVM binding, so this port carries Tesseract and
 * reports {@code ocr_source="tesseract"}. That stays contract-compatible because kb-rag-server tests
 * the marker for presence, not for a particular value ({@code ParsedDocument.ParsedPage#ocrApplied}) -
 * see the README's deviations section.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
public class TesseractOcrEngine implements OcrEngine {

    private static final String TESSERACT_CLASS = "net.sourceforge.tess4j.Tesseract";

    private final ParserProperties properties;

    /**
     * Bounds a single page's OCR call under the configured timeout from within the parser's own worker
     * thread. Sized off the parser worker count, not off a fixed small number: this pool is shared
     * across every in-flight parse, so a fixed size would quietly become the narrowest point in the
     * chain - the pool exists to carry a timeout, not to cap throughput.
     */
    private final ExecutorService ocrCallExecutor;

    private final ThreadLocal<Object> engineHandle = ThreadLocal.withInitial(this::newTesseract);

    public TesseractOcrEngine(ParserProperties properties) {
        this.properties = properties;
        this.ocrCallExecutor = Executors.newFixedThreadPool(
                Math.max(1, properties.getMaxWorkers()),
                runnable -> {
                    Thread thread = new Thread(runnable, "ocr-worker");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    @Override
    public String recognize(byte[] pngBytes, int pageNo) {
        Future<String> future = ocrCallExecutor.submit(() -> runInference(pngBytes));
        try {
            return future.get(properties.getOcrTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            log.info("ocr page skipped, reason=interrupted, pageNo={}", pageNo);
            return null;
        } catch (Exception ex) {
            // Any inference failure or timeout degrades this one page, never the document.
            future.cancel(true);
            log.info("ocr page skipped, reason=inference_failed_or_timeout, pageNo={}, timeoutSeconds={}, detail={}",
                    pageNo, properties.getOcrTimeoutSeconds(), ex.toString());
            return null;
        }
    }

    @Override
    public String ocrSource() {
        return ParserConstants.OCR_SOURCE_TESSERACT;
    }

    private String runInference(byte[] pngBytes) throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(pngBytes));
        if (image == null) {
            return null;
        }
        Object tesseract = engineHandle.get();
        Method doOcr = tesseract.getClass().getMethod("doOCR", BufferedImage.class);
        String text = (String) doOcr.invoke(tesseract, image);
        return text == null || Whitespace.isBlank(text) ? null : Whitespace.strip(text);
    }

    private Object newTesseract() {
        try {
            Class<?> tesseractClass = Class.forName(TESSERACT_CLASS);
            Object instance = tesseractClass.getDeclaredConstructor().newInstance();
            tesseractClass.getMethod("setLanguage", String.class)
                    .invoke(instance, properties.getOcrLanguage());
            String dataPath = properties.getOcrDataPath();
            if (dataPath != null && !dataPath.isBlank()) {
                tesseractClass.getMethod("setDatapath", String.class).invoke(instance, dataPath);
            }
            return instance;
        } catch (ReflectiveOperationException ex) {
            // Unreachable in a correctly started process: ensureOcrEngineReady() has already proven the
            // class loads. Kept as a hard failure rather than a null handle so a broken classpath can
            // never masquerade as "this page had no text".
            throw new IllegalStateException("failed to construct the tesseract engine", ex);
        }
    }

    /**
     * @return true when the optional tess4j dependency is on the classpath
     */
    public static boolean isTesseractInstalled() {
        try {
            Class.forName(TESSERACT_CLASS);
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }
}
