package io.kbrag.parser.error;

/**
 * Raised when file_ext is not present in the parser registry.
 *
 * @author owlzhangfq@gmail.com
 */
public class UnsupportedFormatException extends ParseException {

    public UnsupportedFormatException(String message) {
        super(message);
    }
}
