package io.kbrag.parser.parser;

import io.kbrag.parser.error.ErrorCode;
import io.kbrag.parser.error.ParseException;
import io.kbrag.parser.model.PageContent;
import io.kbrag.parser.model.ParseData;
import io.kbrag.parser.security.ZipSafetyGuard;
import io.kbrag.parser.support.NumberFormatting;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * XLSX parser: every worksheet becomes one logical page plus one markdown table.
 *
 * <p>Security note: xlsx is a zip package of XML parts, so it goes through the zip-slip / zip-bomb
 * precheck first (requirement §4.2). POI hardens its own XML reading against XXE, so nothing further
 * is needed here.
 *
 * <p>Formula cells are read through their cached result rather than being evaluated - the counterpart
 * of openpyxl's {@code data_only=True}. Evaluating them would mean executing workbook-authored
 * expressions on this service's behalf, which is both a needless attack surface and the wrong answer:
 * the cached value is what the document actually showed a reader.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
public class ExcelParser implements DocumentParser {

    /** Matches Python's {@code str(datetime)} rendering of an openpyxl date cell. */
    private static final DateTimeFormatter DATE_CELL_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String SHEET_HEADING_PREFIX = "## Sheet: ";
    private static final String SECTION_SEPARATOR = "\n\n";

    @Override
    public ParseData parse(byte[] content, String filename) {
        ZipSafetyGuard.ensureZipIsSafe(content);

        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            List<PageContent> pages = new ArrayList<>();
            List<String> markdownParts = new ArrayList<>();
            for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
                Sheet sheet = workbook.getSheetAt(index);
                List<List<String>> rows = readRows(sheet);
                String sheetMarkdown = SHEET_HEADING_PREFIX + sheet.getSheetName()
                        + SECTION_SEPARATOR + TableMarkdown.render(rows);
                pages.add(PageContent.builder()
                        .pageNo(index + 1)
                        .text(TableMarkdown.renderPlain(rows))
                        .markdown(sheetMarkdown)
                        .build());
                markdownParts.add(sheetMarkdown);
            }
            return ParseData.builder()
                    .markdown(String.join(SECTION_SEPARATOR, markdownParts))
                    .pages(pages)
                    .build();
        } catch (Exception ex) {
            log.error("xlsx parse failed, errorCode={}, filename={}", ErrorCode.PARSE_FAILED, filename);
            throw new ParseException("failed to parse xlsx: " + ex.getMessage(), ex);
        }
    }

    /**
     * Reads a sheet as a rectangular grid.
     *
     * <p>Every row is padded to the sheet's widest row, because a markdown table with ragged rows
     * renders as broken markdown - openpyxl's {@code iter_rows} pads to the used range for the same
     * reason.
     */
    private static List<List<String>> readRows(Sheet sheet) {
        int width = 0;
        for (Row row : sheet) {
            width = Math.max(width, row.getLastCellNum());
        }
        List<List<String>> rows = new ArrayList<>();
        for (Row row : sheet) {
            List<String> cells = new ArrayList<>(width);
            for (int column = 0; column < width; column++) {
                cells.add(stringify(row.getCell(column)));
            }
            rows.add(cells);
        }
        return rows;
    }

    /**
     * Renders one cell the way {@code str()} renders the corresponding openpyxl value, so both
     * implementations put the same characters into the retrieval corpus.
     */
    private static String stringify(Cell cell) {
        if (cell == null) {
            return "";
        }
        CellType type = cell.getCellType() == CellType.FORMULA
                ? cell.getCachedFormulaResultType()
                : cell.getCellType();
        return switch (type) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                    ? cell.getLocalDateTimeCellValue().format(DATE_CELL_FORMAT)
                    : NumberFormatting.formatNumeric(cell.getNumericCellValue());
            // Python renders a bool as "True"/"False"; Java's own "true"/"false" would differ.
            case BOOLEAN -> cell.getBooleanCellValue() ? "True" : "False";
            case ERROR, BLANK, _NONE, FORMULA -> "";
        };
    }
}
