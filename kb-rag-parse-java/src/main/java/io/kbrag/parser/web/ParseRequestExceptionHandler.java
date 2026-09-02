package io.kbrag.parser.web;

import io.kbrag.parser.model.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.UUID;

/**
 * Turns malformed requests into the same envelope a parse failure produces.
 *
 * <p>Without this, a request that forgot {@code file_ext} would come back as Spring's default error
 * body - a different shape from every other answer this service gives, which kb-rag-server's client
 * would fail to unwrap and report as something other than what actually went wrong.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@RestControllerAdvice
public class ParseRequestExceptionHandler {

    /**
     * @param ex the missing part/parameter or oversize upload
     * @return a PARSE_FAILED envelope naming what was wrong with the request
     */
    @ExceptionHandler({MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class,
            MaxUploadSizeExceededException.class})
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Void> handleMalformedRequest(Exception ex) {
        String requestId = UUID.randomUUID().toString().replace("-", "");
        log.error("request rejected, errorCode=PARSE_FAILED, reason={}", ex.getMessage());
        return ApiResponse.failed(ex.getMessage(), requestId);
    }
}
