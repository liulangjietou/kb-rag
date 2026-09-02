package io.kbrag.parser.error;

/**
 * Raised when the uploaded file exceeds MAX_FILE_SIZE_BYTES.
 *
 * @author owlzhangfq@gmail.com
 */
public class FileTooLargeException extends ParseException {

    public FileTooLargeException(String message) {
        super(message);
    }
}
