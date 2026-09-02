package io.kbrag.parser.parser;

import io.kbrag.parser.config.ParserConstants;
import io.kbrag.parser.config.ParserProperties;
import io.kbrag.parser.error.ErrorCode;
import io.kbrag.parser.error.ParseException;
import io.kbrag.parser.model.PageContent;
import io.kbrag.parser.model.ParseData;
import io.kbrag.parser.ocr.OcrEngine;
import io.kbrag.parser.ocr.OcrEngineFactory;
import io.kbrag.parser.support.Whitespace;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * PDF parser backed by Apache PDFBox: per-page text, embedded images, and scanned-page renders.
 *
 * <p>A page with no usable text layer is rendered whole to a 150dpi PNG ({@code kind=page_render}) so
 * an OCR tier can read it. This parser runs no model itself by default: OCR is kb-rag-server's VLM
 * responsibility (M3-CONTRACTS.md §0), and the optional local engine (M8-CONTRACTS.md §0.4) is the one
 * exception, off unless explicitly configured. A scanned page's embedded images are not separately
 * extracted - the whole page is already captured as one render, so pulling its rasters out too would
 * just double-count the same content.
 *
 * <p>An embedded image is reported once per distinct image object rather than once per placement. A
 * header logo is a single object drawn on every page, and reporting it per page would cost
 * kb-rag-server one vision call per page for one picture: a 250-page document used to report the same
 * two rasters ~250 times, each base64'd into the response and described again by the vision model.
 * One distinct image therefore yields one asset, whose placeholder marks its first occurrence - which
 * also keeps the placeholder ids in markdown unique, the property kb-rag-server's substitution relies
 * on. Were one id to appear on 250 pages, its description would be spliced into all 250 and pollute
 * every chunk.
 *
 * <p>A page whose text layer extracts as garbled glyph soup ({@link GarbledTextDetector}) is
 * downgraded onto the same scanned-page path: its unusable text is dropped, the page is rendered, and
 * a warning is recorded.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PdfParser implements DocumentParser {

    private static final String PAGE_HEADING_PREFIX = "## Page ";
    private static final String SECTION_SEPARATOR = "\n\n";
    private static final String PNG_FORMAT = "png";
    private static final String JPEG_SUFFIX = "jpg";
    private static final String JPEG_SUFFIX_ALT = "jpeg";

    private final ParserProperties properties;
    private final OcrEngineFactory ocrEngineFactory;

    @Override
    public ParseData parse(byte[] content, String filename) {
        try (PDDocument document = Loader.loadPDF(content)) {
            return extract(document, filename);
        } catch (ParseException ex) {
            throw ex;
        } catch (IOException ex) {
            log.error("pdf parse failed, errorCode={}, filename={}, stage=open",
                    ErrorCode.PARSE_FAILED, filename);
            throw new ParseException("failed to open pdf: " + ex.getMessage(), ex);
        } catch (Exception ex) {
            log.error("pdf parse failed, errorCode={}, filename={}, stage=extract",
                    ErrorCode.PARSE_FAILED, filename);
            throw new ParseException("failed to extract pdf text: " + ex.getMessage(), ex);
        }
    }

    private ParseData extract(PDDocument document, String filename) throws IOException {
        ImageAssetCollector collector = new ImageAssetCollector(properties);
        OcrEngine ocrEngine = ocrEngineFactory.getOcrEngine();
        PDFRenderer renderer = new PDFRenderer(document);
        PDFTextStripper stripper = new PDFTextStripper();

        List<PageContent> pages = new ArrayList<>();
        List<String> markdownParts = new ArrayList<>();
        List<String> pageWarnings = new ArrayList<>();
        // Document-wide, so an image drawn on many pages is reported once.
        Set<Long> seenObjects = new HashSet<>();

        for (int pageIndex = 0; pageIndex < document.getNumberOfPages(); pageIndex++) {
            int pageNo = pageIndex + 1;
            String text = extractPageText(stripper, document, pageNo);

            if (Whitespace.strip(text).length() >= properties.getScannedPageTextThreshold()
                    && GarbledTextDetector.isGarbled(text, properties.getGarbledPageValidCharRatioPct())) {
                // Wrong-codepoint glyph soup must never reach chunking or indexing; blanking it here
                // makes this page take exactly the scanned-page path below, where the render (and
                // whatever OCR reads it) becomes its only text source.
                log.info("pdf page text garbled, falling back to page render, pageNo={}, filename={}",
                        pageNo, filename);
                pageWarnings.add("page " + pageNo + " text layer is garbled (likely a missing/broken "
                        + "ToUnicode CMap in an embedded font); fell back to page render");
                text = "";
            }

            boolean scanned = Whitespace.strip(text).length() < properties.getScannedPageTextThreshold();
            String ocrSource = null;
            List<String> placeholderLines;

            if (scanned) {
                ScannedPageOutcome outcome = renderAndOcrScannedPage(
                        renderer, pageIndex, pageNo, collector, ocrEngine);
                placeholderLines = outcome.placeholderLines();
                if (outcome.ocrText() != null) {
                    text = outcome.ocrText();
                    ocrSource = ocrEngine.ocrSource();
                }
            } else {
                placeholderLines = extractEmbeddedImages(
                        document.getPage(pageIndex), pageNo, collector, seenObjects);
            }

            StringBuilder pageMarkdown = new StringBuilder()
                    .append(PAGE_HEADING_PREFIX).append(pageNo).append(SECTION_SEPARATOR).append(text);
            if (!placeholderLines.isEmpty()) {
                pageMarkdown.append(SECTION_SEPARATOR).append(String.join("\n", placeholderLines));
            }
            markdownParts.add(pageMarkdown.toString());
            pages.add(PageContent.builder()
                    .pageNo(pageNo)
                    .text(text)
                    .markdown(pageMarkdown.toString())
                    .scanned(scanned)
                    .ocrSource(ocrSource)
                    .build());
        }

        List<String> warnings = new ArrayList<>(collector.getWarnings());
        warnings.addAll(pageWarnings);
        return ParseData.builder()
                .markdown(String.join(SECTION_SEPARATOR, markdownParts))
                .pages(pages)
                .images(new ArrayList<>(collector.getImages()))
                .warnings(warnings)
                .build();
    }

    /**
     * Extracts one page's text layer.
     *
     * <p>Overridable, and an instance method for that reason alone: a pdf with a genuinely broken
     * ToUnicode CMap cannot be produced by PDFBox's own writer, which always emits a valid one, so the
     * garbled-page fallback has no way to be exercised end to end except by substituting the extraction
     * result at this seam.
     *
     * @param stripper the reusable stripper, already bound to this document
     * @param document the open document
     * @param pageNo   1-based page number
     * @return the page's extracted text
     * @throws IOException on extraction failure
     */
    protected String extractPageText(PDFTextStripper stripper, PDDocument document, int pageNo)
            throws IOException {
        stripper.setStartPage(pageNo);
        stripper.setEndPage(pageNo);
        return stripper.getText(document);
    }

    /** What one scanned page produced: its placeholder line(s), and any text a local engine read. */
    private record ScannedPageOutcome(List<String> placeholderLines, String ocrText) {
    }

    /**
     * Renders a text-less page to PNG so an OCR/VLM tier can read it, and additionally attempts this
     * service's own local OCR on that same render - a no-op when no local engine is configured.
     *
     * <p>The render is skipped outright once the document image cap is reached <i>and</i> no local
     * engine is configured, because then nobody would ever read the PNG: the collector would discard
     * it and no text would come out of it. With a local engine on, the page is still rendered past the
     * cap - the cap bounds the images the response carries, not this service's ability to read a page.
     */
    private ScannedPageOutcome renderAndOcrScannedPage(PDFRenderer renderer, int pageIndex, int pageNo,
                                                       ImageAssetCollector collector, OcrEngine ocrEngine)
            throws IOException {
        boolean wantsAsset = collector.hasCapacity(pageNo, ImageKind.PAGE_RENDER);
        boolean ocrEnabled = properties.isLocalOcrEnabled();
        if (!wantsAsset && !ocrEnabled) {
            return new ScannedPageOutcome(List.of(), null);
        }
        BufferedImage rendered = renderer.renderImageWithDPI(
                pageIndex, ParserConstants.SCANNED_PAGE_RENDER_DPI, ImageType.RGB);
        byte[] pngBytes = toPng(rendered);
        String imageId = wantsAsset
                ? collector.tryAdd(pageNo, ImageKind.PAGE_RENDER, MediaTypes.IMAGE_PNG, pngBytes)
                : null;
        List<String> placeholderLines = imageId == null
                ? List.of()
                : List.of(ImageAssetCollector.placeholder(imageId));
        return new ScannedPageOutcome(placeholderLines, ocrEngine.recognize(pngBytes, pageNo));
    }

    /**
     * Pulls the raster images drawn on a (non-scanned) page that no earlier page has already reported.
     *
     * <p>Form XObjects are descended into, because a picture placed through a reusable form group is
     * still a picture on the page. Forms and images share one seen-set, which gives three properties at
     * once: an image object is reported once per document, a form group is expanded once per document
     * (so the pictures inside a repeated header form are not re-reported either), and a form that
     * references itself cannot loop.
     */
    private static List<String> extractEmbeddedImages(PDPage page, int pageNo,
                                                      ImageAssetCollector collector,
                                                      Set<Long> seenObjects) {
        List<String> placeholderLines = new ArrayList<>();
        collectFromResources(page.getResources(), pageNo, collector, seenObjects, placeholderLines);
        return placeholderLines;
    }

    private static void collectFromResources(PDResources resources, int pageNo,
                                             ImageAssetCollector collector, Set<Long> seenObjects,
                                             List<String> placeholderLines) {
        if (resources == null) {
            return;
        }
        for (COSName name : resources.getXObjectNames()) {
            long objectNumber = objectNumberOf(resources, name);
            // Marked before the attempt, so a malformed object is tried once for the document rather
            // than once per page that draws it. An inline entry (objectNumber 0) has no identity to
            // deduplicate on and is simply always processed.
            if (objectNumber > 0 && !seenObjects.add(objectNumber)) {
                continue;
            }
            PDXObject xObject;
            try {
                xObject = resources.getXObject(name);
            } catch (IOException | RuntimeException ex) {
                // A handful of malformed or unsupported objects must not fail the whole document.
                log.info("embedded image extraction skipped, pageNo={}, objectNumber={}, reason=xobject_unreadable",
                        pageNo, objectNumber);
                continue;
            }
            if (xObject instanceof PDFormXObject form) {
                collectFromResources(form.getResources(), pageNo, collector, seenObjects, placeholderLines);
                continue;
            }
            if (!(xObject instanceof PDImageXObject image)) {
                continue;
            }
            EncodedImage encoded = encode(image, pageNo, objectNumber);
            if (encoded == null) {
                continue;
            }
            String imageId = collector.tryAdd(pageNo, ImageKind.EMBEDDED, encoded.mediaType(), encoded.bytes());
            if (imageId != null) {
                placeholderLines.add(ImageAssetCollector.placeholder(imageId));
            }
        }
    }

    /**
     * @return the indirect object number identifying this XObject, or 0 when the entry is written
     *         inline and therefore has no identity to deduplicate on
     */
    private static long objectNumberOf(PDResources resources, COSName name) {
        COSDictionary xObjects = resources.getCOSObject().getCOSDictionary(COSName.XOBJECT);
        if (xObjects == null) {
            return 0;
        }
        COSBase item = xObjects.getItem(name);
        return item instanceof COSObject cosObject ? cosObject.getObjectNumber() : 0;
    }

    /** Image bytes ready to hand out, with the MIME type that describes them. */
    private record EncodedImage(byte[] bytes, String mediaType) {
    }

    /**
     * Encodes one embedded image for transport.
     *
     * <p>A JPEG is passed through in its original DCT bytes rather than being decoded and re-encoded:
     * re-encoding a photographic page scan as PNG routinely multiplies its size several-fold, and the
     * response carries every image base64'd. Everything else - Flate rasters, CCITT fax, JBIG2 - has no
     * standalone file form inside the pdf, so it is decoded once and written as PNG.
     */
    private static EncodedImage encode(PDImageXObject image, int pageNo, long objectNumber) {
        try {
            String suffix = image.getSuffix();
            String normalized = suffix == null ? "" : suffix.toLowerCase(Locale.ROOT);
            if (JPEG_SUFFIX.equals(normalized) || JPEG_SUFFIX_ALT.equals(normalized)) {
                try (InputStream raw = image.getStream().getCOSObject().createRawInputStream()) {
                    return new EncodedImage(raw.readAllBytes(), MediaTypes.IMAGE_JPEG);
                }
            }
            return new EncodedImage(toPng(image.getImage()), MediaTypes.IMAGE_PNG);
        } catch (IOException | RuntimeException ex) {
            // Typically a codec this build has no decoder for (jpeg2000, jbig2); skip that one image.
            log.info("embedded image extraction skipped, pageNo={}, objectNumber={}, reason=decode_failed",
                    pageNo, objectNumber);
            return null;
        }
    }

    private static byte[] toPng(BufferedImage image) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ImageIO.write(image, PNG_FORMAT, buffer);
        return buffer.toByteArray();
    }
}
