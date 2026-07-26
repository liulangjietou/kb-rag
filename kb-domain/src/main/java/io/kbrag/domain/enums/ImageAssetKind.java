package io.kbrag.domain.enums;

import java.util.Locale;

/**
 * Origin of an image asset, which is what decides how its textual proxy is used.
 *
 * <p>An embedded image is part of a larger text, so its proxy is spliced back where the placeholder
 * stood and takes part in the ordinary splitting. A standalone upload has no surrounding text, so its
 * proxy becomes a chunk of its own. A page render is the scanned page case: there is no text layer at
 * all and the vision model is the only way to read the page.
 *
 * @author owlzhangfq@gmail.com
 */
public enum ImageAssetKind {

    /** Image embedded in a pdf or docx. */
    EMBEDDED,

    /** Whole page rendered as an image because it carries no text layer. */
    PAGE_RENDER,

    /** The uploaded file itself is an image. */
    STANDALONE;

    /**
     * Lower case literal used in transport payloads.
     *
     * @return wire value
     */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Resolves a parser supplied literal, defaulting to {@link #EMBEDDED}.
     *
     * @param code wire value, may be blank or unknown
     * @return matching kind, {@link #EMBEDDED} when unrecognised
     */
    public static ImageAssetKind from(String code) {
        if (code == null || code.isBlank()) {
            return EMBEDDED;
        }
        for (ImageAssetKind kind : values()) {
            if (kind.name().equalsIgnoreCase(code.trim())) {
                return kind;
            }
        }
        return EMBEDDED;
    }
}
