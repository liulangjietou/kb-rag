package io.kbrag.parser.error;

/**
 * Base class for every error raised inside the parsing pipeline.
 *
 * <p>Any subclass of this is caught at the API boundary and normalized to a PARSE_FAILED response.
 * Anything that is <i>not</i> a subclass is caught by the same boundary's last-resort guard, so the
 * distinction is not about whether a failure is handled - it is about whether the message is safe and
 * useful to hand back to the caller verbatim, which is exactly what these carry.
 *
 * @author owlzhangfq@gmail.com
 */
public class ParseException extends RuntimeException {

    public ParseException(String message) {
        super(message);
    }

    public ParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
