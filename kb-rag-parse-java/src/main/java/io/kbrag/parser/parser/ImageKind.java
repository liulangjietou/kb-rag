package io.kbrag.parser.parser;

/**
 * The {@code kind} values an {@link io.kbrag.parser.model.ImageAsset} may carry, centralized so the
 * pdf and docx parsers never spell them out as inline literals.
 *
 * @author owlzhangfq@gmail.com
 */
public final class ImageKind {

    /** An inline raster image found in the source pdf/docx. */
    public static final String EMBEDDED = "embedded";

    /** A whole scanned pdf page rendered to PNG so an OCR/VLM tier can read it. */
    public static final String PAGE_RENDER = "page_render";

    private ImageKind() {
    }
}
