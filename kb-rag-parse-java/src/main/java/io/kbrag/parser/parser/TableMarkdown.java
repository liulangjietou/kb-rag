package io.kbrag.parser.parser;

import java.util.List;

/**
 * Renders a rectangular cell grid as a markdown table, shared by the docx, xlsx and csv parsers.
 *
 * <p>The first row becomes the header, as it does in the Python service: a tabular export without a
 * header row is rare enough, and guessing wrong is worse than a first data row rendered as a header -
 * the cells are all still there either way, which is what retrieval cares about.
 *
 * @author owlzhangfq@gmail.com
 */
public final class TableMarkdown {

    private static final String CELL_SEPARATOR = " | ";
    private static final String ROW_PREFIX = "| ";
    private static final String ROW_SUFFIX = " |";
    private static final String HEADER_RULE_CELL = "---";

    /** Plain-text rows use tabs, matching the Python service's per-row {@code "\t".join(...)}. */
    private static final String PLAIN_CELL_SEPARATOR = "\t";

    private TableMarkdown() {
    }

    /**
     * @param rows cell grid, first row treated as the header
     * @return the markdown table, or an empty string for an empty grid
     */
    public static String render(List<List<String>> rows) {
        if (rows.isEmpty()) {
            return "";
        }
        List<String> header = rows.get(0);
        StringBuilder builder = new StringBuilder();
        appendRow(builder, header);
        builder.append('\n').append(ROW_PREFIX);
        for (int i = 0; i < header.size(); i++) {
            if (i > 0) {
                builder.append(CELL_SEPARATOR);
            }
            builder.append(HEADER_RULE_CELL);
        }
        builder.append(ROW_SUFFIX);
        for (int i = 1; i < rows.size(); i++) {
            builder.append('\n');
            appendRow(builder, rows.get(i));
        }
        return builder.toString();
    }

    /**
     * @param rows cell grid
     * @return the tab-separated plain-text rendering used for {@code pages[].text}
     */
    public static String renderPlain(List<List<String>> rows) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                builder.append('\n');
            }
            builder.append(String.join(PLAIN_CELL_SEPARATOR, rows.get(i)));
        }
        return builder.toString();
    }

    private static void appendRow(StringBuilder builder, List<String> cells) {
        builder.append(ROW_PREFIX).append(String.join(CELL_SEPARATOR, cells)).append(ROW_SUFFIX);
    }
}
