package io.kbrag.parser.error;

/**
 * Raised by the zip precheck: zip-slip path traversal, decompressed size bomb, or entry-count bomb
 * detected in a docx/xlsx package.
 *
 * @author owlzhangfq@gmail.com
 */
public class ZipSafetyException extends ParseException {

    public ZipSafetyException(String message) {
        super(message);
    }

    public ZipSafetyException(String message, Throwable cause) {
        super(message, cause);
    }
}
