package io.kbrag.parser;

import io.kbrag.parser.chat.PythonRegexTranslator;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.PatternSyntaxException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Python-to-Java regex bridge that lets a mapping profile written for the Python service run here
 * unchanged.
 *
 * <p>These cases are the ones that would silently corrupt group numbering if the scan were a naive
 * text substitution - non-capturing groups, lookarounds, escaped parentheses, parentheses inside a
 * character class - plus the two behaviours the contract depends on: an underscore in a group name,
 * which Java rejects outright, and Unicode-aware character classes.
 *
 * @author owlzhangfq@gmail.com
 */
class PythonRegexTranslatorTest {

    @Test
    void translatesPythonNamedGroupsIncludingUnderscoredNames() {
        // send_time is the contract's own group name, and Java's own named-group syntax cannot express
        // it: a group name must match [a-zA-Z][a-zA-Z0-9]*.
        PythonRegexTranslator.TranslatedPattern translated = PythonRegexTranslator.translate(
                "^(?P<send_time>\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}) (?P<sender>.+)$");

        Matcher matcher = translated.pattern().matcher("2024-01-01 10:00:00 张三");
        assertTrue(matcher.matches());
        assertEquals("2024-01-01 10:00:00", matcher.group(translated.groupsByName().get("send_time")));
        assertEquals("张三", matcher.group(translated.groupsByName().get("sender")));
    }

    @Test
    void acceptsJavaNamedGroupSyntaxToo() {
        PythonRegexTranslator.TranslatedPattern translated =
                PythonRegexTranslator.translate("^(?<sender>\\S+): (?<content>.*)$");

        Matcher matcher = translated.pattern().matcher("alice: hi");
        assertTrue(matcher.matches());
        assertEquals("alice", matcher.group(translated.groupsByName().get("sender")));
        assertEquals("hi", matcher.group(translated.groupsByName().get("content")));
    }

    @Test
    void numbersGroupsCorrectlyAlongsideNonCapturingGroupsAndLookarounds() {
        // Only (?P<send_time>...), (plain) and (?P<sender>...) open capturing groups; the (?:, (?=,
        // (?! and (?<= forms must not consume a number, or every capture after them reads the wrong
        // span.
        PythonRegexTranslator.TranslatedPattern translated = PythonRegexTranslator.translate(
                "^(?:prefix )?(?=\\d)(?P<send_time>\\d+)(?!x) (plain)(?<=n) (?P<sender>\\w+)$");

        assertEquals(1, translated.groupsByName().get("send_time"));
        assertEquals(3, translated.groupsByName().get("sender"),
                "the un-named (plain) group occupies number 2");

        Matcher matcher = translated.pattern().matcher("prefix 123 plain alice");
        assertTrue(matcher.matches());
        assertEquals("123", matcher.group(translated.groupsByName().get("send_time")));
        assertEquals("plain", matcher.group(2));
        assertEquals("alice", matcher.group(translated.groupsByName().get("sender")));
    }

    @Test
    void doesNotCountAnEscapedOrCharacterClassParenthesis() {
        PythonRegexTranslator.TranslatedPattern translated =
                PythonRegexTranslator.translate("^\\((?P<sender>[(a-z)]+)\\) (?P<send_time>\\d+)$");

        assertEquals(1, translated.groupsByName().get("sender"));
        assertEquals(2, translated.groupsByName().get("send_time"));

        Matcher matcher = translated.pattern().matcher("(alice) 1737800000");
        assertTrue(matcher.matches());
        assertEquals("alice", matcher.group(translated.groupsByName().get("sender")));
    }

    @Test
    void handlesALiteralClosingBracketAtTheStartOfACharacterClass() {
        // "[]()]" is a class of ']', '(' and ')' - a naive scan would end the class at the first ']'
        // and then treat the following '(' as a group.
        PythonRegexTranslator.TranslatedPattern translated =
                PythonRegexTranslator.translate("^[]()]+(?P<sender>\\w+) (?P<send_time>\\d+)$");

        assertEquals(1, translated.groupsByName().get("sender"));
        assertEquals(2, translated.groupsByName().get("send_time"));
    }

    @Test
    void translatesAPythonNamedBackreference() {
        PythonRegexTranslator.TranslatedPattern translated =
                PythonRegexTranslator.translate("^(?P<sender>\\w+)-(?P=sender) (?P<send_time>\\d+)$");

        assertTrue(translated.pattern().matcher("alice-alice 1737800000").matches());
        assertFalse(translated.pattern().matcher("alice-bob 1737800000").matches());
    }

    @Test
    void characterClassesAreUnicodeAwareLikePython3() {
        // Python 3's \w matches CJK; Java's does not unless UNICODE_CHARACTER_CLASS is set. A profile
        // that relies on it must behave the same on both services.
        PythonRegexTranslator.TranslatedPattern translated =
                PythonRegexTranslator.translate("^(?P<sender>\\w+) (?P<send_time>\\d+)$");

        assertTrue(translated.pattern().matcher("张三 1737800000").matches());
    }

    @Test
    void rejectsABackreferenceToAnUndefinedGroup() {
        assertThrows(PatternSyntaxException.class,
                () -> PythonRegexTranslator.translate("^(?P=nope)(?P<send_time>\\d+)(?P<sender>\\w+)$"));
    }

    @Test
    void reportsWhichNamesAPatternCaptures() {
        PythonRegexTranslator.TranslatedPattern translated =
                PythonRegexTranslator.translate("^(?P<send_time>\\d+) (?P<sender>\\w+)$");

        assertTrue(translated.hasGroup("send_time"));
        assertTrue(translated.hasGroup("sender"));
        assertFalse(translated.hasGroup("content"));
    }
}
