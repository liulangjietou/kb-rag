package io.kbrag.domain.service;

import io.kbrag.common.exception.BizException;
import io.kbrag.domain.model.SplitChunk;
import io.kbrag.domain.model.SplitParams;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Table row level splitting for spreadsheet exports rendered as markdown tables.
 *
 * <p>Each data row becomes one chunk. When header join is on, the markdown header block is prepended so
 * every row remains interpretable without the rest of the sheet.
 *
 * @author owlzhangfq@gmail.com
 */
@Component
@RequiredArgsConstructor
public class TableRowTextSplitter implements TextSplitter {

    /** Strategy code persisted in the split fingerprint. */
    public static final String STRATEGY_CODE = "table_row";

    private static final Pattern TABLE_ROW = Pattern.compile("^\\|.*\\|$");

    private final FixedLengthTextSplitter fixedLengthTextSplitter;

    @Override
    public String strategy() {
        return STRATEGY_CODE;
    }

    @Override
    public List<SplitChunk> split(String text, SplitParams params) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String[] lines = text.split("\n", -1);
        List<SplitChunk> chunks = new ArrayList<>();
        StringBuilder prose = new StringBuilder();
        int index = 0;
        while (index < lines.length) {
            String line = lines[index];
            if (TABLE_ROW.matcher(line.trim()).matches()) {
                if (!prose.isEmpty()) {
                    chunks.addAll(fixedLengthTextSplitter.split(prose.toString(), params));
                    prose.setLength(0);
                }
                int tableEnd = index + 1;
                while (tableEnd < lines.length && TABLE_ROW.matcher(lines[tableEnd].trim()).matches()) {
                    tableEnd++;
                }
                chunks.addAll(splitTable(lines, index, tableEnd, params));
                index = tableEnd;
            } else {
                if (!prose.isEmpty()) {
                    prose.append('\n');
                }
                prose.append(line);
                index++;
            }
        }
        if (!prose.isEmpty()) {
            chunks.addAll(fixedLengthTextSplitter.split(prose.toString(), params));
        }
        int maxRows = params.getTableRowMaxChunks();
        if (maxRows > 0 && chunks.size() > maxRows) {
            throw BizException.invalidParam("table row split would produce " + chunks.size()
                    + " chunks, exceeding the limit of " + maxRows);
        }
        return chunks;
    }

    private List<SplitChunk> splitTable(String[] lines, int start, int end, SplitParams params) {
        List<String> tableLines = new ArrayList<>(end - start);
        for (int i = start; i < end; i++) {
            tableLines.add(lines[i]);
        }
        if (tableLines.size() < 2) {
            return fixedLengthTextSplitter.split(String.join("\n", tableLines), params);
        }
        String header = tableLines.get(0);
        int dataStart = isSeparatorRow(tableLines.get(1)) ? 2 : 1;
        String headerBlock = params.isTablePrependHeader() && dataStart > 1
                ? header + "\n" + tableLines.get(1) + "\n" : null;
        List<String> rowBlocks = new ArrayList<>();
        for (int row = dataStart; row < tableLines.size(); row++) {
            String rowLine = tableLines.get(row);
            if (isSeparatorRow(rowLine)) {
                continue;
            }
            rowBlocks.add(headerBlock == null ? rowLine : headerBlock + rowLine);
        }
        List<SplitChunk> rows = new ArrayList<>(rowBlocks.size());
        for (String block : rowBlocks) {
            rows.addAll(fixedLengthTextSplitter.split(block, params));
        }
        return rows;
    }

    private boolean isSeparatorRow(String line) {
        String trimmed = line.trim().toLowerCase(Locale.ROOT);
        return trimmed.chars().allMatch(ch -> ch == '|' || ch == '-' || ch == ':' || ch == ' ');
    }
}