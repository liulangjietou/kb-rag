package io.kbrag.parser.parser;

import io.kbrag.parser.support.Whitespace;

/**
 * Heuristic for a broken pdf text layer (M3-CONTRACTS.md §2.1, garbled-page extension).
 *
 * <p>A pdf whose embedded subset font lacks a usable ToUnicode CMap extracts as wrong-codepoint
 * "glyph soup" - CJK content surfacing as, say, Myanmar or box-drawing glyphs while ASCII digits
 * survive. Such a page has plenty of characters, so the scanned-page length threshold never catches
 * it; without this check the soup would be chunked and indexed, producing whole segments that no query
 * can ever match. The page is therefore declared garbled when the share of characters inside
 * recognizable Unicode ranges falls below the configured percentage, and falls back to the
 * scanned-page path instead.
 *
 * @author owlzhangfq@gmail.com
 */
public final class GarbledTextDetector {

    /**
     * Unicode ranges whose characters count as recognizable: ASCII, general punctuation, CJK
     * punctuation, kana, CJK ideographs (base and extension A), and half/fullwidth forms.
     */
    private static final int[][] RECOGNIZABLE_CHAR_RANGES = {
            {0x0020, 0x007E},  // ASCII printable
            {0x2000, 0x206F},  // general punctuation (dashes, quotes, ellipsis)
            {0x3000, 0x303F},  // CJK symbols and punctuation
            {0x3040, 0x30FF},  // hiragana + katakana
            {0x3400, 0x4DBF},  // CJK unified ideographs extension A
            {0x4E00, 0x9FFF},  // CJK unified ideographs
            {0xFF00, 0xFFEF},  // halfwidth/fullwidth forms
    };

    private static final int PERCENT = 100;

    private GarbledTextDetector() {
    }

    /**
     * @param text                 the extracted text layer of one page
     * @param validCharRatioPctMin minimum share of recognizable characters, in percent
     * @return true when the text layer is unusable and the page should fall back to a render
     */
    public static boolean isGarbled(String text, int validCharRatioPctMin) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        int meaningful = 0;
        int recognizable = 0;
        // Iterating by code point rather than by char so a supplementary-plane character counts once,
        // not twice as an unrecognized surrogate pair.
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            if (Whitespace.isSpace(codePoint)) {
                continue;
            }
            meaningful++;
            if (isRecognizable(codePoint)) {
                recognizable++;
            }
        }
        if (meaningful == 0) {
            return false;
        }
        // Integer comparison of recognizable/meaningful < pct/100, avoiding floating point.
        return (long) recognizable * PERCENT < (long) validCharRatioPctMin * meaningful;
    }

    private static boolean isRecognizable(int codePoint) {
        for (int[] range : RECOGNIZABLE_CHAR_RANGES) {
            if (codePoint >= range[0] && codePoint <= range[1]) {
                return true;
            }
        }
        return false;
    }
}
