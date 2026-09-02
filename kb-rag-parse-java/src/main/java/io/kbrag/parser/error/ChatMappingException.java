package io.kbrag.parser.error;

/**
 * Raised when a chat log mapping profile cannot be loaded, or cannot resolve a required target field
 * against the actual shape of the upload - the {@code content} column of a csv/xlsx header
 * (M3-CONTRACTS.md §2.2), a {@code txt:} line template that matches nothing (M8-CONTRACTS.md §0.1), or
 * an {@code html:} selector that matches no node (§0.2).
 *
 * @author owlzhangfq@gmail.com
 */
public class ChatMappingException extends ParseException {

    public ChatMappingException(String message) {
        super(message);
    }

    public ChatMappingException(String message, Throwable cause) {
        super(message, cause);
    }
}
