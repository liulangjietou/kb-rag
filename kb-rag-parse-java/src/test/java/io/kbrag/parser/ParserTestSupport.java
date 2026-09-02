package io.kbrag.parser;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Sample-file builders shared by the tests, the counterpart of the Python suite's {@code conftest.py}.
 *
 * <p>Every sample is generated in code, with the same libraries the parsers themselves use, rather than
 * being committed as a binary fixture. A checked-in binary is opaque: nobody reviewing a test can see
 * what the file actually contains, and when a parser's behaviour changes there is no way to tell
 * whether the fixture or the expectation was wrong.
 *
 * @author owlzhangfq@gmail.com
 */
public final class ParserTestSupport {

    /**
     * Well above the default scanned-page threshold of 20, so a sample using this text is never
     * mistaken for a scanned page unless a test explicitly asks for one.
     */
    public static final String NORMAL_PAGE_TEXT = "Hello kb-rag PDF, this page has a normal text layer.";

    private ParserTestSupport() {
    }

    /** A minimal one-page PDF with a real text layer. */
    public static byte[] pdfBytes(String text) {
        try (PDDocument document = new PDDocument()) {
            addTextPage(document, text);
            return toBytes(document);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** A multi-page PDF whose pages carry distinct text, so a boundary mix-up is visible. */
    public static byte[] multiPagePdfBytes(int pages) {
        try (PDDocument document = new PDDocument()) {
            for (int pageNo = 1; pageNo <= pages; pageNo++) {
                addTextPage(document, "Page " + pageNo + " body text, long enough to have a real text layer.");
            }
            return toBytes(document);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** A one-page PDF with a real text layer plus one embedded raster image. */
    public static byte[] pdfWithEmbeddedImageBytes() {
        try (PDDocument document = new PDDocument()) {
            PDPage page = addTextPage(document,
                    "Hello kb-rag PDF, this page has a normal text layer and an embedded image.");
            PDImageXObject image = PDImageXObject.createFromByteArray(document, tinyPngBytes(), "logo");
            try (PDPageContentStream stream = new PDPageContentStream(
                    document, page, PDPageContentStream.AppendMode.APPEND, true)) {
                stream.drawImage(image, 72, 500, 100, 100);
            }
            return toBytes(document);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * A multi-page PDF whose pages all draw the <i>same</i> raster, the way a header logo appears
     * throughout a real document: one image object referenced by every page, which is exactly the shape
     * the parser's per-object deduplication keys on.
     */
    public static byte[] pdfWithRepeatedImageBytes(int pages) {
        try (PDDocument document = new PDDocument()) {
            PDImageXObject logo = null;
            for (int i = 0; i < pages; i++) {
                PDPage page = addTextPage(document,
                        "Hello kb-rag PDF, this page has a normal text layer and a header logo.");
                if (logo == null) {
                    logo = PDImageXObject.createFromByteArray(document, tinyPngBytes(), "logo");
                }
                try (PDPageContentStream stream = new PDPageContentStream(
                        document, page, PDPageContentStream.AppendMode.APPEND, true)) {
                    stream.drawImage(logo, 72, 500, 100, 100);
                }
            }
            return toBytes(document);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** A PDF with no text layer at all, simulating scanned pages. */
    public static byte[] scannedPdfBytes(int pages) {
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                document.addPage(new PDPage(PDRectangle.A4));
            }
            return toBytes(document);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * A one-page PDF with zero native text layer - so it is still detected as scanned - whose rendered
     * pixmap nonetheless contains legible glyph pixels, for exercising real OCR inference end to end.
     */
    public static byte[] scannedPdfWithOcrableTextBytes(String text) {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(300, 100));
            document.addPage(page);
            PDImageXObject image = PDImageXObject.createFromByteArray(
                    document, renderedTextPngBytes(text), "scan");
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.drawImage(image, 0, 0, 300, 100);
            }
            return toBytes(document);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static PDPage addTextPage(PDDocument document, String text) throws IOException {
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);
        try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
            stream.beginText();
            stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
            stream.newLineAtOffset(72, 720);
            stream.showText(text);
            stream.endText();
        }
        return page;
    }

    /** A tiny valid PNG, built without pulling in an extra imaging library just for fixtures. */
    public static byte[] tinyPngBytes() {
        BufferedImage image = new BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.LIGHT_GRAY);
        graphics.fillRect(0, 0, 40, 40);
        graphics.dispose();
        return toPngBytes(image);
    }

    private static byte[] renderedTextPngBytes(String text) {
        BufferedImage image = new BufferedImage(600, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 600, 200);
        graphics.setColor(Color.BLACK);
        graphics.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 72));
        graphics.drawString(text, 20, 130);
        graphics.dispose();
        return toPngBytes(image);
    }

    private static byte[] toPngBytes(BufferedImage image) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", buffer);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return buffer.toByteArray();
    }

    private static byte[] toBytes(PDDocument document) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        document.save(buffer);
        return buffer.toByteArray();
    }

    /** A docx with a heading, a paragraph and a 2x2 table. */
    public static byte[] docxBytes(String heading, String paragraph) {
        try (XWPFDocument document = new XWPFDocument()) {
            XWPFParagraph headingParagraph = document.createParagraph();
            headingParagraph.setStyle("Heading1");
            headingParagraph.createRun().setText(heading);

            document.createParagraph().createRun().setText(paragraph);

            XWPFTable table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("Name");
            table.getRow(0).getCell(1).setText("Age");
            table.getRow(1).getCell(0).setText("Alice");
            table.getRow(1).getCell(1).setText("30");

            return toBytes(document);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** A docx with one embedded picture between two paragraphs. */
    public static byte[] docxWithImageBytes(String paragraph) {
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText(paragraph);

            XWPFParagraph pictureParagraph = document.createParagraph();
            XWPFRun run = pictureParagraph.createRun();
            byte[] png = tinyPngBytes();
            run.addPicture(new ByteArrayInputStream(png), Document.PICTURE_TYPE_PNG, "tiny.png",
                    640_000, 640_000);

            document.createParagraph().createRun().setText("after the image");
            return toBytes(document);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to build the docx sample", ex);
        }
    }

    private static byte[] toBytes(XWPFDocument document) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        document.write(buffer);
        return buffer.toByteArray();
    }

    /** A single-sheet workbook with a header row and two data rows. */
    public static byte[] xlsxBytes() {
        return chatXlsxBytes(List.of("Name", "Age"),
                List.of(List.of("Alice", 30), List.of("Bob", 25)));
    }

    public static byte[] csvBytes() {
        return "Name,Age\r\nAlice,30\r\nBob,25\r\n".getBytes(StandardCharsets.UTF_8);
    }

    /** A chat-log csv with an arbitrary header/rows shape. */
    public static byte[] chatCsvBytes(List<String> header, List<List<Object>> rows) {
        StringBuilder builder = new StringBuilder();
        appendCsvRow(builder, header.stream().map(Object.class::cast).toList());
        for (List<Object> row : rows) {
            appendCsvRow(builder, row);
        }
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendCsvRow(StringBuilder builder, List<Object> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            String cell = String.valueOf(cells.get(i));
            if (cell.indexOf(',') >= 0 || cell.indexOf('"') >= 0 || cell.indexOf('\n') >= 0) {
                builder.append('"').append(cell.replace("\"", "\"\"")).append('"');
            } else {
                builder.append(cell);
            }
        }
        builder.append("\r\n");
    }

    /** The same shape as {@link #chatCsvBytes}, but as a single-sheet workbook. */
    public static byte[] chatXlsxBytes(List<String> header, List<List<Object>> rows) {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Sheet1");
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < header.size(); i++) {
                headerRow.createCell(i).setCellValue(header.get(i));
            }
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                List<Object> cells = rows.get(r);
                for (int c = 0; c < cells.size(); c++) {
                    Object value = cells.get(c);
                    if (value instanceof Number number) {
                        row.createCell(c).setCellValue(number.doubleValue());
                    } else {
                        row.createCell(c).setCellValue(String.valueOf(value));
                    }
                }
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            workbook.write(buffer);
            return buffer.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
