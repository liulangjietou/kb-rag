package io.kbrag.common.exception;

import io.kbrag.common.api.ErrorCode;
import lombok.Getter;

/**
 * Business exception carrying a contract error code.
 *
 * <p>Thrown by application and domain services; translated into the unified error envelope by the
 * single global exception handler in the API module.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
public class BizException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Contract error code answered to the caller. */
    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /**
     * Shortcut for parameter validation failures.
     *
     * @param message safe message
     * @return exception mapped to {@link ErrorCode#INVALID_PARAM}
     */
    public static BizException invalidParam(String message) {
        return new BizException(ErrorCode.INVALID_PARAM, message);
    }

    /**
     * Shortcut for missing resources.
     *
     * @param message safe message
     * @return exception mapped to {@link ErrorCode#NOT_FOUND}
     */
    public static BizException notFound(String message) {
        return new BizException(ErrorCode.NOT_FOUND, message);
    }

    /**
     * Shortcut for authentication failures.
     *
     * @param message safe message
     * @return exception mapped to {@link ErrorCode#UNAUTHORIZED}
     */
    public static BizException unauthorized(String message) {
        return new BizException(ErrorCode.UNAUTHORIZED, message);
    }

    /**
     * Shortcut for authorisation failures of an already authenticated caller.
     *
     * @param message safe message
     * @return exception mapped to {@link ErrorCode#FORBIDDEN}
     */
    public static BizException forbidden(String message) {
        return new BizException(ErrorCode.FORBIDDEN, message);
    }
}
