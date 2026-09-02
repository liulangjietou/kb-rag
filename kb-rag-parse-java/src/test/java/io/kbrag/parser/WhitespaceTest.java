package io.kbrag.parser;

import io.kbrag.parser.support.Whitespace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whitespace handling with Python's semantics.
 *
 * <p>Every case here is one the JDK's own API gets "wrong" for this service's purposes - not because
 * the JDK is mistaken, but because it draws the line somewhere else than Python does, and the two
 * implementations of this service have to put the same characters into the corpus.
 *
 * @author owlzhangfq@gmail.com
 */
class WhitespaceTest {

    /** What every {@code &nbsp;} in an HTML page decodes to. */
    private static final char NBSP = 0x00a0;

    /** FIGURE SPACE - another Zs separator Java's isWhitespace rejects. */
    private static final char FIGURE_SPACE = 0x2007;

    /** FILE SEPARATOR - a control Java calls whitespace but isSpaceChar does not. */
    private static final char FILE_SEPARATOR = 0x001c;

    @Test
    void nonBreakingSpaceIsWhitespace() {
        // String.strip() and Character.isWhitespace both say no; Python's str.strip() says yes, and
        // an HTML heading of "标题&nbsp;" must not keep an invisible trailing character here while
        // losing it there.
        assertFalse(Character.isWhitespace(NBSP), "precondition: the JDK disagrees");
        assertTrue(Whitespace.isSpace(NBSP));
        assertEquals("a" + NBSP + "b", Whitespace.strip("  a" + NBSP + "b  " + NBSP));
    }

    @Test
    void unicodeSeparatorsAndAsciiControlsAreBothCovered() {
        // Neither Character.isWhitespace nor isSpaceChar covers both groups on its own.
        assertTrue(Whitespace.isSpace(FIGURE_SPACE));
        assertTrue(Whitespace.isSpace(FILE_SEPARATOR));
        assertTrue(Whitespace.isBlank("" + FIGURE_SPACE + FILE_SEPARATOR + NBSP));
    }

    @Test
    void collapseMatchesPythonSplitJoin() {
        assertEquals("a b", Whitespace.collapse("  a" + NBSP + "b  "));
        assertEquals("a b c", Whitespace.collapse("a \n\t b c"));
        assertEquals("", Whitespace.collapse("   " + NBSP + " "));
        assertEquals("", Whitespace.collapse(null));
    }

    @Test
    void stripAndBlankHandleEdgeInputs() {
        assertEquals("", Whitespace.strip(null));
        assertEquals("", Whitespace.strip(""));
        assertEquals("", Whitespace.strip("   "));
        assertTrue(Whitespace.isBlank(null));
        assertTrue(Whitespace.isBlank(""));
        assertFalse(Whitespace.isBlank("x"));
    }

    @Test
    void removeAllStripsEveryInteriorSpaceToo() {
        // The key form used for case- and space-insensitive header matching: a header pasted out of a
        // rendered table routinely carries a non-breaking space.
        assertEquals("createtime", Whitespace.removeAll("  create" + NBSP + "time ").toLowerCase());
    }

    @Test
    void supplementaryCharactersSurviveIntact() {
        // Iterating by char rather than by code point would split a surrogate pair.
        String emoji = "😀";
        assertEquals(emoji, Whitespace.strip(" " + emoji + " "));
        assertEquals(emoji + " " + emoji, Whitespace.collapse(emoji + "   " + emoji));
    }
}
