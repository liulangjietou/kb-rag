package io.kbrag.common.exception;

/**
 * Classification of model provider failures.
 *
 * <p>The pipeline stores the classification instead of a raw stack trace so the console can show an
 * actionable reason for a failed task.
 */
public enum ProviderErrorType {

    /** Credential rejected by the provider. */
    AUTH_FAILED,

    /** Quota exhausted or account in arrears. */
    QUOTA_EXCEEDED,

    /** Network level failure, provider unreachable. */
    NETWORK_UNREACHABLE,

    /** Requested model name does not exist for this provider. */
    MODEL_NOT_FOUND,

    /** Input exceeded the model token limit. */
    INPUT_TOO_LONG,

    /** Returned vector dimension does not match the configured dimension. */
    DIMENSION_MISMATCH,

    /** Any other provider side failure. */
    UNKNOWN
}
