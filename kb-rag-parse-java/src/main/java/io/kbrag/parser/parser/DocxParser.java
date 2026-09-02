package io.kbrag.parser.parser;

import io.kbrag.parser.config.ParserProperties;
import io.kbrag.parser.error.ErrorCode;
import io.kbrag.parser.error.ParseException;
import io.kbrag.parser.model.PageContent;
import io.kbrag.parser.model.ParseData;
import io.kbrag.parser.security.ZipSafetyGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackageRelationship;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFStyles;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DOCX parser: full-document text (paragraphs + tables) and embedded images.
 *
 * <p>Word documents carry no reliable page-boundary information without a layout engine - page breaks
 * depend on rendering, fonts and margins - so the whole document is returned as a single logical page
 * (page_no=1), and every extracted image is attributed to that same page (M3-CONTRACTS.md §2.1). Real
 * pagination is a TODO if a downstream consumer ever needs it.
 *
 * <p>Security note: docx is a zip package of XML parts. The zip-slip / zip-bomb precheck runs before
 * the bytes reach POI (requirement §4.2); XXE hardening for the XML parts is POI's own, audited in
 * {@link io.kbrag.parser.security.XmlHardening}.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocxParser implements DocumentParser {

    /** Word style name/id prefix that marks a heading, matched case-insensitively. */
    private static final String HEADING_STYLE_PREFIX = "heading";

    /** docx has no page boundaries, so everything belongs to this single logical page. */
    private static final int SINGLE_PAGE_NO = 1;

    /** Where embedded pictures live inside the docx zip package. */
    private static final String MEDIA_PREFIX = "word/media/";

    /** The package folder a relationship target is relative to when it is not package-absolute. */
    private static final String DOCUMENT_PART_FOLDER = "word/";

    private static final String SECTION_SEPARATOR = "\n\n";
    private static final int MAX_HEADING_LEVEL = 6;

    /**
     * A run's inline or floating picture references its image through {@code <a:blip r:embed="rIdN"/>}
     * inside {@code <w:drawing>}. Matching the serialized paragraph XML for the relationship ids is far
     * simpler than walking the OOXML drawing schema, and the ids in document order are all this parser
     * needs. The namespace prefix is left open because XmlBeans does not guarantee which one it emits.
     */
    private static final Pattern EMBED_ID_PATTERN = Pattern.compile("(?:[A-Za-z0-9]+:)?embed=\"([^\"]+)\"");

    private final ParserProperties properties;

    @Override
    public ParseData parse(byte[] content, String filename) {
        ZipSafetyGuard.ensureZipIsSafe(content);

        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(content))) {
            Map<String, byte[]> mediaBytesByPath = readMediaEntries(content);
            Map<String, String> ridToMediaPath = readImageRelationships(document);
            Set<String> consumedPaths = new HashSet<>();
            ImageAssetCollector collector = new ImageAssetCollector(properties);

            List<String> markdownParts = new ArrayList<>();
            List<String> plainTextParts = new ArrayList<>();
            for (IBodyElement block : document.getBodyElements()) {
                if (block instanceof XWPFParagraph paragraph) {
                    markdownParts.add(paragraphToMarkdown(paragraph, document.getStyles()));
                    plainTextParts.add(paragraph.getText());
                    markdownParts.addAll(collectParagraphImagePlaceholders(
                            paragraph, ridToMediaPath, mediaBytesByPath, consumedPaths, collector));
                } else if (block instanceof XWPFTable table) {
                    markdownParts.add(tableToMarkdown(table));
                }
            }
            markdownParts.addAll(
                    collectLeftoverImagePlaceholders(mediaBytesByPath, consumedPaths, collector));

            String markdown = joinNonEmpty(markdownParts);
            PageContent page = PageContent.builder()
                    .pageNo(SINGLE_PAGE_NO)
                    .text(String.join("\n", plainTextParts))
                    .markdown(markdown)
                    .scanned(false)
                    .build();
            return ParseData.builder()
                    .markdown(markdown)
                    .pages(List.of(page))
                    .images(new ArrayList<>(collector.getImages()))
                    .warnings(new ArrayList<>(collector.getWarnings()))
                    .build();
        } catch (Exception ex) {
            log.error("docx parse failed, errorCode={}, filename={}", ErrorCode.PARSE_FAILED, filename);
            throw new ParseException("failed to parse docx: " + ex.getMessage(), ex);
        }
    }

    private static String joinNonEmpty(List<String> parts) {
        List<String> kept = new ArrayList<>(parts.size());
        for (String part : parts) {
            if (part != null && !part.isEmpty()) {
                kept.add(part);
            }
        }
        return String.join(SECTION_SEPARATOR, kept);
    }

    /**
     * Renders a paragraph, turning a Word heading style into the matching markdown heading level.
     *
     * <p>The style is resolved by display name first and by style id second, because the two spell the
     * same heading differently ("Heading 1" vs "Heading1") and which one a document carries depends on
     * the tool that wrote it.
     */
    private static String paragraphToMarkdown(XWPFParagraph paragraph, XWPFStyles styles) {
        String text = paragraph.getText();
        int level = headingLevelOf(paragraph, styles);
        return level > 0 ? "#".repeat(level) + " " + text : text;
    }

    private static int headingLevelOf(XWPFParagraph paragraph, XWPFStyles styles) {
        String styleId = paragraph.getStyleID();
        if (styleId == null || styleId.isEmpty()) {
            return 0;
        }
        String styleName = styleId;
        if (styles != null) {
            XWPFStyle style = styles.getStyle(styleId);
            if (style != null && style.getName() != null && !style.getName().isEmpty()) {
                styleName = style.getName();
            }
        }
        String normalized = styleName.toLowerCase(Locale.ROOT);
        if (!normalized.startsWith(HEADING_STYLE_PREFIX)) {
            return 0;
        }
        String levelText = normalized.substring(HEADING_STYLE_PREFIX.length()).trim();
        // An unnumbered "Heading" style is still a heading; treat it as level 1, as python-docx does.
        int level = levelText.chars().allMatch(Character::isDigit) && !levelText.isEmpty()
                ? Integer.parseInt(levelText)
                : 1;
        return Math.min(Math.max(level, 1), MAX_HEADING_LEVEL);
    }

    private static String tableToMarkdown(XWPFTable table) {
        List<List<String>> rows = new ArrayList<>();
        for (XWPFTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                cells.add(cell.getText());
            }
            rows.add(cells);
        }
        return TableMarkdown.render(rows);
    }

    /**
     * Reads every {@code word/media/*} entry: the exhaustive set of embedded images, independent of
     * how or where each one is anchored (inline, floating, header/footer, table cell).
     */
    private static Map<String, byte[]> readMediaEntries(byte[] content) throws IOException {
        Map<String, byte[]> mediaBytesByPath = new TreeMap<>();
        try (ZipFile archive = ZipFile.builder()
                .setSeekableByteChannel(new SeekableInMemoryByteChannel(content))
                .get()) {
            Enumeration<ZipArchiveEntry> entries = archive.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().startsWith(MEDIA_PREFIX)) {
                    continue;
                }
                try (InputStream in = archive.getInputStream(entry)) {
                    mediaBytesByPath.put(entry.getName(), in.readAllBytes());
                }
            }
        }
        return mediaBytesByPath;
    }

    /**
     * Maps each image relationship id - as referenced by {@code r:embed} in a run - to the zip path of
     * the media file it points at, so a placeholder can be planted at the paragraph where the image
     * actually occurs.
     *
     * <p>Resolution goes through POI's package API rather than a hand-parse of
     * {@code word/_rels/document.xml.rels}: it is the same information, and it keeps this service free
     * of an XML parser of its own to keep hardened.
     */
    private static Map<String, String> readImageRelationships(XWPFDocument document)
            throws InvalidFormatException {
        Map<String, String> ridToMediaPath = new LinkedHashMap<>();
        PackagePart documentPart = document.getPackagePart();
        for (PackageRelationship relationship : documentPart.getRelationships()) {
            String target = relationship.getTargetURI().toString();
            String normalized = normalizeMediaPath(target);
            if (normalized.startsWith(MEDIA_PREFIX)) {
                ridToMediaPath.put(relationship.getId(), normalized);
            }
        }
        return ridToMediaPath;
    }

    /**
     * Turns a relationship target into the zip path form used by {@link #readMediaEntries}: a target
     * is either already absolute inside the package ({@code /word/media/x.png}) or relative to the
     * document part ({@code media/x.png}).
     */
    private static String normalizeMediaPath(String target) {
        if (target.startsWith("/")) {
            return target.substring(1);
        }
        return DOCUMENT_PART_FOLDER + target;
    }

    /** Emits one placeholder line per image this paragraph references, in document order. */
    private static List<String> collectParagraphImagePlaceholders(
            XWPFParagraph paragraph, Map<String, String> ridToMediaPath,
            Map<String, byte[]> mediaBytesByPath, Set<String> consumedPaths,
            ImageAssetCollector collector) {
        List<String> placeholders = new ArrayList<>();
        Matcher matcher = EMBED_ID_PATTERN.matcher(paragraph.getCTP().xmlText());
        while (matcher.find()) {
            String mediaPath = ridToMediaPath.get(matcher.group(1));
            if (mediaPath == null || consumedPaths.contains(mediaPath)) {
                continue;
            }
            byte[] rawBytes = mediaBytesByPath.get(mediaPath);
            if (rawBytes == null) {
                continue;
            }
            consumedPaths.add(mediaPath);
            String imageId = collector.tryAdd(SINGLE_PAGE_NO, ImageKind.EMBEDDED,
                    MediaTypes.guess(extensionOf(mediaPath)), rawBytes);
            if (imageId != null) {
                placeholders.add(ImageAssetCollector.placeholder(imageId));
            }
        }
        return placeholders;
    }

    /**
     * Placeholders for media files no paragraph scan matched - images anchored in a table cell, a
     * header or a footer - appended once at the end so no embedded image is silently dropped from
     * {@code images[]}.
     */
    private static List<String> collectLeftoverImagePlaceholders(
            Map<String, byte[]> mediaBytesByPath, Set<String> consumedPaths,
            ImageAssetCollector collector) {
        List<String> placeholders = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : mediaBytesByPath.entrySet()) {
            if (consumedPaths.contains(entry.getKey())) {
                continue;
            }
            String imageId = collector.tryAdd(SINGLE_PAGE_NO, ImageKind.EMBEDDED,
                    MediaTypes.guess(extensionOf(entry.getKey())), entry.getValue());
            if (imageId != null) {
                placeholders.add(ImageAssetCollector.placeholder(imageId));
            }
        }
        return placeholders;
    }

    private static String extensionOf(String zipPath) {
        int dot = zipPath.lastIndexOf('.');
        return dot >= 0 ? zipPath.substring(dot + 1) : "";
    }
}
