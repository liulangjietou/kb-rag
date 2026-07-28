package io.kbrag.api.advice;

import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.api.Result;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.JsonUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Single translation point from exception to error envelope.
 *
 * <p>Messages are the ones the throwing layer chose; internal details and stack traces stay in the
 * logs, which is what keeps the API from leaking implementation information.
 *
 * <p><b>Streaming requests get the envelope written by hand.</b> A request that only accepts
 * {@code text/event-stream} (the SSE chat endpoints) cannot be answered through content negotiation:
 * rendering the JSON envelope through the message converters throws {@code HttpMediaTypeNotAcceptableException},
 * the resolver gives up, and what should have been a 401 or 400 reaches the client as a bare 500 -
 * hiding, for instance, an expired console token behind "Internal Server Error". For those requests
 * the envelope is therefore written directly to the response, bypassing negotiation; the client reads
 * the JSON body off the failed fetch before the stream ever starts.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handles business exceptions that already carry a contract error code.
     *
     * @param e        business exception
     * @param request  request being answered, used to detect stream-only accept headers
     * @param response response the envelope is written to when negotiation cannot run
     * @return error envelope with the mapped HTTP status, {@code null} when written by hand
     */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBiz(BizException e, HttpServletRequest request,
                                                  HttpServletResponse response) {
        ErrorCode errorCode = e.getErrorCode();
        if (errorCode == ErrorCode.INTERNAL_ERROR || errorCode == ErrorCode.UPSTREAM_MODEL_ERROR) {
            log.error("request failed, errorCode={}", errorCode, e);
        } else {
            log.info("request rejected, errorCode={}, reason={}", errorCode, e.getMessage());
        }
        Result<Void> body = Result.failure(errorCode, e.getMessage());
        if (acceptsOnlyEventStream(request)) {
            writeEnvelope(response, errorCode.getHttpStatus(), body);
            return null;
        }
        return ResponseEntity.status(errorCode.getHttpStatus()).body(body);
    }

    /**
     * Whether the request cannot negotiate a JSON error body.
     *
     * @param request request being answered
     * @return {@code true} when the accept header names {@code text/event-stream} without a JSON or
     *         wildcard alternative
     */
    private boolean acceptsOnlyEventStream(HttpServletRequest request) {
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        if (accept == null || !accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE)) {
            return false;
        }
        return !accept.contains(MediaType.APPLICATION_JSON_VALUE) && !accept.contains("*/*");
    }

    /**
     * Writes the error envelope straight to the response, outside content negotiation.
     *
     * @param response response to write to
     * @param status   HTTP status of the failure
     * @param body     error envelope
     */
    private void writeEnvelope(HttpServletResponse response, int status, Result<Void> body) {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        try {
            response.getWriter().write(JsonUtil.toJson(body));
        } catch (IOException ioe) {
            log.error("error envelope write failed, errorCode={}", ErrorCode.INTERNAL_ERROR, ioe);
        }
    }

    /**
     * Handles bean validation failures of request bodies.
     *
     * @param e validation exception
     * @return error envelope mapped to {@link ErrorCode#INVALID_PARAM}
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidation(MethodArgumentNotValidException e) {
        FieldError first = e.getBindingResult().getFieldErrors().isEmpty()
                ? null : e.getBindingResult().getFieldErrors().get(0);
        String message = first == null
                ? ErrorCode.INVALID_PARAM.getDefaultMessage()
                : first.getField() + " " + first.getDefaultMessage();
        log.info("request rejected, errorCode={}, reason={}", ErrorCode.INVALID_PARAM, message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.failure(ErrorCode.INVALID_PARAM, message));
    }

    /**
     * Handles missing request parameters.
     *
     * @param e missing parameter exception
     * @return error envelope mapped to {@link ErrorCode#INVALID_PARAM}
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        log.info("request rejected, errorCode={}, reason={}", ErrorCode.INVALID_PARAM, e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.failure(ErrorCode.INVALID_PARAM, e.getParameterName() + " is required"));
    }

    /**
     * Handles uploads rejected by the servlet container before the validator could run.
     *
     * @param e size exception
     * @return error envelope mapped to {@link ErrorCode#INVALID_PARAM}
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> handleUploadSize(MaxUploadSizeExceededException e) {
        log.info("upload rejected, errorCode={}, reason=size limit exceeded", ErrorCode.INVALID_PARAM);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.failure(ErrorCode.INVALID_PARAM, "file exceeds the configured size limit"));
    }

    /**
     * Handles requests whose path matches no handler.
     *
     * <p>Exists because the catch all below would otherwise answer a mistyped URL with
     * {@code 500 INTERNAL_ERROR} and log a full stack trace: the caller is told the service broke when
     * the path is simply wrong, and every wrong URL an integration tries buries real failures under
     * error level noise. A path that does not exist is a client error, so it gets 404 and one info line.
     *
     * @param e resource not found exception
     * @return error envelope mapped to {@link ErrorCode#NOT_FOUND}
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNoResourceFound(NoResourceFoundException e) {
        log.info("request rejected, errorCode={}, path={}", ErrorCode.NOT_FOUND, e.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Result.failure(ErrorCode.NOT_FOUND, "no handler for " + e.getResourcePath()));
    }

    /**
     * Catch all handler; the caller only sees a generic message.
     *
     * @param e        unexpected exception
     * @param request  request being answered, used to detect stream-only accept headers
     * @param response response the envelope is written to when negotiation cannot run
     * @return error envelope mapped to {@link ErrorCode#INTERNAL_ERROR}, {@code null} when written by hand
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnexpected(Exception e, HttpServletRequest request,
                                                         HttpServletResponse response) {
        log.error("request failed, errorCode={}", ErrorCode.INTERNAL_ERROR, e);
        Result<Void> body = Result.failure(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getDefaultMessage());
        if (acceptsOnlyEventStream(request)) {
            writeEnvelope(response, HttpStatus.INTERNAL_SERVER_ERROR.value(), body);
            return null;
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
