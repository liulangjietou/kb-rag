package io.kbrag.parser.parser;

import io.kbrag.parser.error.ErrorCode;
import io.kbrag.parser.error.UnsupportedFormatException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Strategy registry mapping a file extension to its {@link DocumentParser}.
 *
 * <p>Adding support for a new format is a two-line change: implement a {@link DocumentParser} in its
 * own class, then register it below. No change to the controller or to any other parser is required.
 *
 * <p>The extension comes from the request's {@code file_ext} form field rather than from the uploaded
 * filename, deliberately: dispatch must not be steerable by whatever name a caller chose to put on the
 * file.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
public class ParserRegistry {

    private final Map<String, DocumentParser> registry;

    public ParserRegistry(PdfParser pdfParser, DocxParser docxParser, TextParser textParser,
                          ExcelParser excelParser, CsvParser csvParser, HtmlParser htmlParser) {
        this.registry = Map.ofEntries(
                Map.entry("pdf", pdfParser),
                Map.entry("docx", docxParser),
                Map.entry("txt", textParser),
                Map.entry("md", textParser),
                Map.entry("sql", textParser),
                Map.entry("xlsx", excelParser),
                Map.entry("csv", csvParser),
                Map.entry("html", htmlParser),
                Map.entry("htm", htmlParser));
    }

    /**
     * @param fileExt extension declared by the request, with or without a leading dot, any case
     * @return the registered parser
     * @throws UnsupportedFormatException when no parser is registered for the extension
     */
    public DocumentParser getParser(String fileExt) {
        String normalized = normalize(fileExt);
        DocumentParser parser = registry.get(normalized);
        if (parser == null) {
            log.error("unsupported file_ext, errorCode={}, fileExt={}", ErrorCode.PARSE_FAILED, fileExt);
            throw new UnsupportedFormatException("unsupported file_ext '" + fileExt + "', supported: "
                    + new TreeSet<>(registry.keySet()));
        }
        return parser;
    }

    static String normalize(String fileExt) {
        if (fileExt == null) {
            return "";
        }
        String trimmed = fileExt.trim().toLowerCase(Locale.ROOT);
        return trimmed.startsWith(".") ? trimmed.substring(1) : trimmed;
    }
}
