package io.kbrag.parser.web;

import io.kbrag.parser.config.ParserConstants;
import io.kbrag.parser.config.ParserProperties;
import io.kbrag.parser.error.ErrorCode;
import io.kbrag.parser.error.ParseException;
import io.kbrag.parser.model.ApiResponse;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs a parse on the parser thread pool under the shared timeout, normalizing every recoverable
 * failure to the same envelope both endpoints answer with.
 *
 * <p>Two things are deliberate here.
 *
 * <p><b>Why a pool at all,</b> when the servlet container already gives each request a thread: this
 * pool, not the container's, is the service's real concurrency ceiling for parsing. Parsing is CPU-bound
 * - text extraction, page rasterization, image decoding - so letting every accepted connection parse in
 * parallel would thrash a host that has far fewer cores than the container has worker threads. Bounding
 * it here means kb-rag-server can submit as many documents as it likes and they queue instead of
 * competing, which is the same ceiling the Python service establishes with its own executor.
 *
 * <p><b>Why every failure becomes HTTP 200</b> with a PARSE_FAILED envelope: a document this service
 * cannot read is an outcome, not a transport error. kb-rag-server reads {@code code}, and a 5xx would
 * additionally invite retries and circuit breakers to treat a permanently unreadable file as a
 * transient fault.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
public class ParseExecutor {

    private final ExecutorService executor;

    public ParseExecutor(ParserProperties properties) {
        this.executor = Executors.newFixedThreadPool(
                Math.max(1, properties.getMaxWorkers()),
                runnable -> {
                    Thread thread = new Thread(runnable, "parser-worker");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    /**
     * @param fileExt   the requested extension, for diagnostics
     * @param requestId the id echoed back in the envelope
     * @param work      the blocking parse call
     * @param <T>       the payload type
     * @return an OK envelope carrying the result, or a PARSE_FAILED envelope carrying the reason
     */
    public <T> ApiResponse<T> run(String fileExt, String requestId, Callable<T> work) {
        Future<T> future = executor.submit(work);
        try {
            return ApiResponse.ok(future.get(ParserConstants.PARSE_TIMEOUT_SECONDS, TimeUnit.SECONDS), requestId);
        } catch (TimeoutException ex) {
            // Interrupt the worker so a runaway parse stops burning a slot the moment we stop waiting.
            future.cancel(true);
            log.error("parse timeout, errorCode={}, fileExt={}, timeoutSeconds={}",
                    ErrorCode.PARSE_FAILED, fileExt, ParserConstants.PARSE_TIMEOUT_SECONDS);
            return ApiResponse.failed(
                    "parse timed out after " + ParserConstants.PARSE_TIMEOUT_SECONDS + "s", requestId);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            log.error("parse interrupted, errorCode={}, fileExt={}", ErrorCode.PARSE_FAILED, fileExt);
            return ApiResponse.failed("parse was interrupted", requestId);
        } catch (ExecutionException ex) {
            return failureOf(ex.getCause(), fileExt, requestId);
        }
    }

    private static <T> ApiResponse<T> failureOf(Throwable cause, String fileExt, String requestId) {
        if (cause instanceof ParseException) {
            log.error("parse failed, errorCode={}, fileExt={}, reason={}",
                    ErrorCode.PARSE_FAILED, fileExt, cause.getMessage());
            return ApiResponse.failed(cause.getMessage(), requestId);
        }
        // Last-resort guard: report the reason, never a raw stack trace.
        log.error("parse failed unexpectedly, errorCode={}, fileExt={}, reason={}",
                ErrorCode.PARSE_FAILED, fileExt, cause == null ? "unknown" : cause.toString());
        return ApiResponse.failed(
                "unexpected parse error: " + (cause == null ? "unknown" : cause.getMessage()), requestId);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
