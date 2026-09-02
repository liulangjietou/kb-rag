package io.kbrag.parser.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Structured parse result returned on success.
 *
 * @author owlzhangfq@gmail.com
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParseData {

    /**
     * Full document content rendered as markdown; may contain {@code [[IMAGE:{image_id}]]} placeholder
     * lines, each on a line of its own (M3-CONTRACTS.md §2.1).
     */
    private String markdown;

    /** Per-page breakdown. */
    @Builder.Default
    private List<PageContent> pages = new ArrayList<>();

    /**
     * Embedded and page-rendered images extracted from the document; always empty for formats with no
     * image concept (txt/md/sql/csv/xlsx/html) or when a document has none.
     */
    @Builder.Default
    private List<ImageAsset> images = new ArrayList<>();

    /**
     * Non-fatal issues encountered while parsing - an image skipped for exceeding the per-document
     * count or per-image byte cap, a pdf page whose garbled text layer was dropped in favour of a
     * render. Never causes the whole parse to fail (M3-CONTRACTS.md §2.1).
     */
    @Builder.Default
    private List<String> warnings = new ArrayList<>();
}
