package io.kbrag.parser.chat;

/**
 * The normalized message-type buckets (M3-CONTRACTS.md §2.2).
 *
 * @author owlzhangfq@gmail.com
 */
public final class MsgType {

    public static final String TEXT = "text";
    public static final String IMAGE = "image";
    public static final String VOICE = "voice";
    public static final String VIDEO = "video";
    public static final String OTHER = "other";

    /**
     * An HTML image-message node has no textual content of its own - the DOM adapter never downloads
     * the referenced image - so its content is this fixed placeholder, matching the "image message kept
     * with a placeholder content string" semantics the csv/xlsx sources already use.
     */
    public static final String IMAGE_PLACEHOLDER_TEXT = "[IMAGE]";

    private MsgType() {
    }
}
