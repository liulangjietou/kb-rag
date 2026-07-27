package io.kbrag.domain.enums;

import java.util.Locale;

/**
 * Export format one chat import mapping profile targets.
 *
 * <p>The value is the file extension rather than a format family name, because that is the one fact the
 * import path can check without opening the file: a profile whose type does not match the uploaded
 * extension describes different columns, or different line templates, than the file actually has.
 *
 * @author owlzhangfq@gmail.com
 */
public enum SourceMappingType {

    /** Delimited text export, column name mapping. */
    CSV,

    /** Spreadsheet export, column name mapping. */
    XLSX,

    /** Plain text transcript, line templates expressed as regular expressions. */
    TXT,

    /** Exported HTML transcript, node selectors. */
    HTML;

    /**
     * Lower case literal used in the API and in the stored column.
     *
     * @return API side value
     */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Tells whether a profile of this format can read an export that arrived with another.
     *
     * <p>Exact match, with one exception: a delimited file and a spreadsheet are both read through the same
     * column name candidates, so one tabular profile serves both and refusing the pair would force two byte
     * identical rows to exist. A line template and a node selector describe genuinely different documents
     * and never substitute for one another.
     *
     * @param uploaded format of the uploaded file, {@code null} being unreadable
     * @return {@code true} when this profile describes the uploaded format
     */
    public boolean reads(SourceMappingType uploaded) {
        if (uploaded == null) {
            return false;
        }
        return this == uploaded || (tabular() && uploaded.tabular());
    }

    /**
     * Resolves a format from its literal.
     *
     * @param value API value, case insensitive
     * @return matching format, {@code null} when blank or unknown
     */
    public static SourceMappingType from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (SourceMappingType type : values()) {
            if (type.name().equalsIgnoreCase(value.trim())) {
                return type;
            }
        }
        return null;
    }

    /**
     * Tells whether the format is read through column names rather than through a text pattern.
     *
     * @return {@code true} for a delimited file or a spreadsheet
     */
    private boolean tabular() {
        return this == CSV || this == XLSX;
    }
}
