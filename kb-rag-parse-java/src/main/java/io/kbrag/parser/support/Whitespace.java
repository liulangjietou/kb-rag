package io.kbrag.parser.support;

/**
 * Whitespace handling with Python's semantics, so both implementations of this service put the same
 * characters into the retrieval corpus.
 *
 * <p>The JDK and Python disagree about what a space is, and the disagreement lands exactly where this
 * service works. {@code U+00A0} NO-BREAK SPACE - what every {@code &nbsp;} in an HTML page decodes to,
 * and a routine artifact of pdf text extraction - is whitespace to Python's {@code str.strip()} and
 * {@code str.split()}, but not to Java's {@code String.strip()}, {@code String.isBlank()} or
 * {@code Character.isWhitespace}. Left alone, a heading of {@code "标题&nbsp;"} would keep a trailing
 * invisible character here and lose it there; a pdf page holding nothing but non-breaking spaces would
 * be a scanned page there and a text page here.
 *
 * <p>Python's {@code str.isspace()} is reproduced as {@code isWhitespace || isSpaceChar}: the first
 * covers the ASCII controls ({@code U+001C}-{@code U+001F} among them) that {@code isSpaceChar} omits,
 * the second covers the Unicode space separators ({@code U+00A0}, {@code U+2007}, {@code U+202F})
 * that {@code isWhitespace} omits. Neither alone is enough, which is why this class exists rather
 * than a call to either.
 *
 * @author owlzhangfq@gmail.com
 */
public final class Whitespace {

    private Whitespace() {
    }

    /**
     * @param codePoint a Unicode code point
     * @return true when Python's {@code str.isspace()} would say yes
     */
    public static boolean isSpace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    /**
     * @param text any text, may be null
     * @return the text with leading and trailing whitespace removed, as {@code str.strip()} does
     */
    public static String strip(String text) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        int start = 0;
        int end = text.length();
        while (start < end && isSpace(text.codePointAt(start))) {
            start += Character.charCount(text.codePointAt(start));
        }
        while (end > start) {
            int previous = text.codePointBefore(end);
            if (!isSpace(previous)) {
                break;
            }
            end -= Character.charCount(previous);
        }
        return text.substring(start, end);
    }

    /**
     * @param text any text, may be null
     * @return true when the text is null, empty, or entirely whitespace
     */
    public static boolean isBlank(String text) {
        return strip(text).isEmpty();
    }

    /**
     * Collapses every run of whitespace to a single space and trims the ends - the equivalent of
     * Python's {@code " ".join(text.split())}.
     *
     * @param text any text, may be null
     * @return the collapsed text
     */
    public static String collapse(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(text.length());
        boolean pendingSeparator = false;
        int i = 0;
        while (i < text.length()) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            if (isSpace(codePoint)) {
                pendingSeparator = !builder.isEmpty();
                continue;
            }
            if (pendingSeparator) {
                builder.append(' ');
                pendingSeparator = false;
            }
            builder.appendCodePoint(codePoint);
        }
        return builder.toString();
    }

    /**
     * Removes every whitespace character, the key form used for case- and space-insensitive header
     * matching (Python's {@code "".join(name.split())}).
     *
     * @param text any text, may be null
     * @return the text with all whitespace removed
     */
    public static String removeAll(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            if (!isSpace(codePoint)) {
                builder.appendCodePoint(codePoint);
            }
        }
        return builder.toString();
    }
}
