package io.kbrag.common.exception;

import io.kbrag.common.api.ErrorCode;
import lombok.Getter;

/**
 * Failure raised by a model provider implementation, always carrying a classification.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
public class ProviderException extends BizException {

    private static final long serialVersionUID = 1L;

    /** Classified reason surfaced to the console instead of a stack trace. */
    private final ProviderErrorType errorType;

    /** Provider implementation name, for example {@code dashscope}. */
    private final String providerName;

    public ProviderException(String providerName, ProviderErrorType errorType, String message) {
        super(ErrorCode.UPSTREAM_MODEL_ERROR, message);
        this.providerName = providerName;
        this.errorType = errorType;
    }

    public ProviderException(String providerName, ProviderErrorType errorType, String message, Throwable cause) {
        super(ErrorCode.UPSTREAM_MODEL_ERROR, message, cause);
        this.providerName = providerName;
        this.errorType = errorType;
    }
}
