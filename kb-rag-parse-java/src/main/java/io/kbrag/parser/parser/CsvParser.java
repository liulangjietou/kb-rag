package io.kbrag.parser.parser;

import io.kbrag.parser.error.ErrorCode;
import io.kbrag.parser.error.ParseException;
import io.kbrag.parser.model.PageContent;
import io.kbrag.parser.model.ParseData;
import io.kbrag.parser.support.TextDecoder;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/**
 * CSV parser: one page plus a markdown table.
 *
 * <p>Plain text - no zip, no XML - so the only guardrail that applies is the upload size cap.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
public class CsvParser implements DocumentParser {

    @Override
    public ParseData parse(byte[] content, String filename) {
        List<List<String>> rows;
        try {
            String text = TextDecoder.decode(content);
            rows = readRows(text, DelimiterSniffer.sniff(text));
        } catch (IOException | RuntimeException ex) {
            log.error("csv parse failed, errorCode={}, filename={}", ErrorCode.PARSE_FAILED, filename);
            throw new ParseException("failed to parse csv: " + ex.getMessage(), ex);
        }

        String markdown = TableMarkdown.render(rows);
        String plainText = TableMarkdown.renderPlain(rows);
        return ParseData.builder()
                .markdown(markdown)
                .pages(List.of(PageContent.builder().pageNo(1).text(plainText).markdown(markdown).build()))
                .build();
    }

    private static List<List<String>> readRows(String text, char delimiter) throws IOException {
        CSVFormat format = CSVFormat.DEFAULT.builder().setDelimiter(delimiter).get();
        List<List<String>> rows = new ArrayList<>();
        try (CSVParser parser = CSVParser.parse(new StringReader(text), format)) {
            for (CSVRecord record : parser) {
                List<String> cells = new ArrayList<>(record.size());
                for (String cell : record) {
                    cells.add(cell);
                }
                rows.add(cells);
            }
        }
        return rows;
    }
}
