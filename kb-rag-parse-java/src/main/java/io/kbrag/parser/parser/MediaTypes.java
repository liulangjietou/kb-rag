package io.kbrag.parser.parser;

import java.util.Locale;
import java.util.Map;

/**
 * Extension-to-MIME mapping for embedded images, shared by the pdf and docx parsers.
 *
 * @author owlzhangfq@gmail.com
 */
public final class MediaTypes {

    public static final String IMAGE_PNG = "image/png";
    public static final String IMAGE_JPEG = "image/jpeg";

    private static final String DEFAULT_MEDIA_TYPE = "application/octet-stream";

    private static final Map<String, String> EXT_TO_MEDIA_TYPE = Map.ofEntries(
            Map.entry("png", IMAGE_PNG),
            Map.entry("jpg", IMAGE_JPEG),
            Map.entry("jpeg", IMAGE_JPEG),
            Map.entry("gif", "image/gif"),
            Map.entry("bmp", "image/bmp"),
            Map.entry("tif", "image/tiff"),
            Map.entry("tiff", "image/tiff"),
            Map.entry("webp", "image/webp"),
            Map.entry("emf", "image/emf"),
            Map.entry("wmf", "image/wmf"));

    private MediaTypes() {
    }

    /**
     * Maps a raw file extension to a MIME type, defaulting to a generic binary type.
     *
     * @param ext extension with or without a leading dot, any case
     * @return the MIME type
     */
    public static String guess(String ext) {
        if (ext == null || ext.isEmpty()) {
            return DEFAULT_MEDIA_TYPE;
        }
        String normalized = ext.toLowerCase(Locale.ROOT);
        if (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }
        return EXT_TO_MEDIA_TYPE.getOrDefault(normalized, DEFAULT_MEDIA_TYPE);
    }
}
