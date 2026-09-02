package io.kbrag.parser.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One extracted or rendered image, referenced by a markdown placeholder.
 *
 * <p>{@code kind=embedded} is an inline raster found in the source pdf/docx; {@code kind=page_render}
 * is a whole scanned pdf page rendered to PNG so an OCR/VLM tier can read it (M3-CONTRACTS.md §2.1).
 * This service produces the image bytes only: the vision text proxy, the object storage upload and the
 * placeholder substitution all happen on the kb-rag-server side.
 *
 * @author owlzhangfq@gmail.com
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImageAsset {

    /** Matches the {@code [[IMAGE:{image_id}]]} placeholder in markdown. */
    private String imageId;

    /** 1-based page this image belongs to. */
    private int pageNo;

    /** {@code embedded} or {@code page_render}. */
    private String kind;

    /** MIME type, e.g. {@code image/png}. */
    private String mediaType;

    /** Base64-encoded raw image bytes. */
    private String contentBase64;
}
