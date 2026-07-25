package io.kbrag.api.advice;

import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.api.Result;
import io.kbrag.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Single translation point from exception to error envelope.
 *
 * <p>Messages are the ones the throwing layer chose; internal details and stack traces stay in the
 * logs, which is what keeps the API from leaking implementation information.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handles business exceptions that already carry a contract error code.
     *
     * @param e business exception
     * @return error envelope with the mapped HTTP status
     */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBiz(BizException e) {
        ErrorCode errorCode = e.getErrorCode();
        if (errorCode == ErrorCode.INTERNAL_ERROR || errorCode == ErrorCode.UPSTREAM_MODEL_ERROR) {
            log.error("request failed, errorCode={}", errorCode, e);
        } else {
            log.info("request rejected, errorCode={}, reason={}", errorCode, e.getMessage());
        }
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(Result.failure(errorCode, e.getMessage()));
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
     * Catch all handler; the caller only sees a generic message.
     *
     * @param e unexpected exception
     * @return error envelope mapped to {@link ErrorCode#INTERNAL_ERROR}
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnexpected(Exception e) {
        log.error("request failed, errorCode={}", ErrorCode.INTERNAL_ERROR, e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.failure(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getDefaultMessage()));
    }
}
