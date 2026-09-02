package io.kbrag.parser.parser;

import java.util.HashMap;
import java.util.Map;

/**
 * Best-effort CSV delimiter detection, the counterpart of Python's {@code csv.Sniffer}.
 *
 * <p>The heuristic is the useful half of what Sniffer does: a real delimiter appears the <i>same</i>
 * number of times on every row, because every row has the same number of columns. Candidates are
 * scored by how consistently they do that across the sample and, among equally consistent ones, the
 * declaration order below wins - which is why a comma-separated file with a stray semicolon inside one
 * quoted cell still comes out as comma-separated.
 *
 * <p>Falls back to a comma, matching the Python service's {@code csv.excel} fallback: a
 * single-column file has no delimiter to find, and the comma parses it correctly regardless.
 *
 * @author owlzhangfq@gmail.com
 */
public final class DelimiterSniffer {

    /** Order matters: it is the tie-break between equally consistent candidates. */
    private static final char[] CANDIDATES = {',', ';', '\t'};

    private static final char DEFAULT_DELIMITER = ',';

    /** Same sample size as the Python service passes to Sniffer. */
    private static final int SAMPLE_CHARS = 4096;

    /** Enough rows to tell a consistent delimiter from a coincidence, without scanning a huge file. */
    private static final int MAX_SAMPLED_ROWS = 20;

    private DelimiterSniffer() {
    }

    /**
     * @param text decoded csv text
     * @return the detected delimiter, or a comma when none is consistent
     */
    public static char sniff(String text) {
        String sample = text.length() > SAMPLE_CHARS ? text.substring(0, SAMPLE_CHARS) : text;
        String[] lines = sample.split("\r?\n", -1);

        char best = DEFAULT_DELIMITER;
        int bestCount = 0;
        for (char candidate : CANDIDATES) {
            int consistentCount = consistentOccurrencesPerRow(lines, candidate);
            if (consistentCount > bestCount) {
                bestCount = consistentCount;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * @return the per-row occurrence count when every sampled row agrees on it and it is non-zero,
     *         otherwise 0 - meaning this candidate is not a delimiter of this file
     */
    private static int consistentOccurrencesPerRow(String[] lines, char candidate) {
        Map<Integer, Integer> countFrequency = new HashMap<>();
        int sampledRows = 0;
        for (String line : lines) {
            if (line.isEmpty() || sampledRows >= MAX_SAMPLED_ROWS) {
                continue;
            }
            sampledRows++;
            countFrequency.merge(countOutsideQuotes(line, candidate), 1, Integer::sum);
        }
        if (sampledRows == 0 || countFrequency.size() != 1) {
            return 0;
        }
        return countFrequency.keySet().iterator().next();
    }

    /**
     * Counts occurrences outside quoted sections, so a delimiter character that only ever appears
     * inside quoted free text is not mistaken for the file's actual separator.
     */
    private static int countOutsideQuotes(String line, char candidate) {
        int count = 0;
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                inQuotes = !inQuotes;
            } else if (ch == candidate && !inQuotes) {
                count++;
            }
        }
        return count;
    }
}
