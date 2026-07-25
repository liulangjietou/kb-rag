package io.kbrag.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.common.context.RequestIdHolder;
import lombok.Getter;
import lombok.ToString;

import java.io.Serializable;

/**
 * Envelope returned by every management REST endpoint.
 *
 * <p>Success bodies carry {@code code=OK} plus the payload, failures carry the business error code
 * and a safe message. The {@code request_id} is always echoed so a caller can correlate a response
 * with server side logs.
 *
 * @param <T> payload type
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@ToString
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class Result<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Business code, {@link ErrorCode#name()}. */
    private final String code;

    /** Human readable message, never carries stack traces or internal details. */
    private final String message;

    /** Payload, {@code null} on failure. */
    private final T data;

    /** Correlation id propagated across the whole call chain. */
    @JsonProperty("request_id")
    private final String requestId;

    private Result(String code, String message, T data, String requestId) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.requestId = requestId;
    }

    /**
     * Builds a successful envelope with a payload.
     *
     * @param data payload, may be {@code null} for void endpoints
     * @param <T>  payload type
     * @return success result carrying the current request id
     */
    public static <T> Result<T> success(T data) {
        return new Result<>(ErrorCode.OK.name(), ErrorCode.OK.getDefaultMessage(), data, RequestIdHolder.get());
    }

    /**
     * Builds a failure envelope.
     *
     * @param errorCode business error code
     * @param message   safe message shown to the caller
     * @param <T>       payload type
     * @return failure result carrying the current request id
     */
    public static <T> Result<T> failure(ErrorCode errorCode, String message) {
        return new Result<>(errorCode.name(), message, null, RequestIdHolder.get());
    }
}
