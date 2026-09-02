package io.kbrag.parser.error;

/**
 * String error codes used in the API response envelope (M1-CONTRACTS.md §5/§6).
 *
 * <p>Success responses use {@link #OK}; every parse failure - unsupported format, security rejection,
 * oversized file, timeout, or an unexpected exception raised by an underlying library - is normalized
 * to {@link #PARSE_FAILED} with a human-readable message and never leaks a stack trace.
 *
 * @author owlzhangfq@gmail.com
 */
public final class ErrorCode {

    public static final String OK = "OK";
    public static final String PARSE_FAILED = "PARSE_FAILED";

    private ErrorCode() {
    }
}
