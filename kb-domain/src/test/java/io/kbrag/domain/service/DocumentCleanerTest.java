package io.kbrag.domain.service;

import io.kbrag.domain.model.CleanRules;
import io.kbrag.domain.model.ParsedDocument;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the fixed four step order and the independence of each switch.
 *
 * <p>The order is the part worth pinning down. Two of the cases below only pass in the contractual order:
 * the header detection has to see the untouched parse output, and the masking has to be the last word so no
 * replacement can put a value back after it was hidden.
 *
 * @author owlzhangfq@gmail.com
 */
class DocumentCleanerTest {

    private final DocumentCleaner cleaner =
            new DocumentCleaner(new HeaderFooterDetector(), new TextDesensitizer());

    @Test
    void shouldReturnTheInputWhenNoRuleIsActive() {
        String markdown = "untouched body with 13800138000";
        assertSame(markdown, cleaner.clean(markdown, List.of(), CleanRules.defaults()));
    }

    @Test
    void shouldRunTheFourStepsInTheContractualOrder() {
        // The replacement writes a phone number into the text. Masking runs last, so the injected number is
        // masked too; any other order would let a replacement re-expose a value.
        CleanRules rules = new CleanRules();
        rules.setStripHeaderFooter(true);
        rules.setStripWatermarkPatterns(List.of("机密"));
        rules.getRegexReplacements().add(replacement("CONTACT", "13800138000"));
        rules.getDesensitize().setEnabled(true);

        String markdown = String.join("\n",
                "ACME Internal", "机密 first paragraph", "CONTACT",
                "ACME Internal", "机密 second paragraph");
        String cleaned = cleaner.clean(markdown, List.of(
                page(1, "ACME Internal\n机密 first paragraph"),
                page(2, "ACME Internal\n机密 second paragraph")), rules);

        assertFalse(cleaned.contains("ACME Internal"), "step one removed the running header");
        assertFalse(cleaned.contains("机密"), "step two removed the watermark");
        assertFalse(cleaned.contains("CONTACT"), "step three applied the replacement");
        assertTrue(cleaned.contains("138****8000"), "step four masked what step three injected");
        assertFalse(cleaned.contains("13800138000"), "the injected number must not survive unmasked");
    }

    @Test
    void shouldStripHeaderFooterOnItsOwn() {
        CleanRules rules = new CleanRules();
        rules.setStripHeaderFooter(true);

        String cleaned = cleaner.clean("ACME Internal\nbody one\nACME Internal\nbody two", List.of(
                page(1, "ACME Internal\nbody one"),
                page(2, "ACME Internal\nbody two")), rules);

        assertEquals("body one\nbody two", cleaned);
    }

    @Test
    void shouldKeepTheHeaderWhileItsSwitchIsOff() {
        CleanRules rules = new CleanRules();
        rules.getDesensitize().setEnabled(true);

        String cleaned = cleaner.clean("ACME Internal\nbody 13800138000\nACME Internal", List.of(
                page(1, "ACME Internal\nbody"),
                page(2, "ACME Internal")), rules);

        assertTrue(cleaned.contains("ACME Internal"), "only the masking switch was on");
        assertTrue(cleaned.contains("138****8000"));
    }

    @Test
    void shouldStripWatermarksOnItsOwn() {
        CleanRules rules = new CleanRules();
        rules.setStripWatermarkPatterns(List.of("内部资料\\s*", "DRAFT"));

        String cleaned = cleaner.clean("内部资料 body text DRAFT here", List.of(), rules);

        assertEquals("body text  here", cleaned);
    }

    @Test
    void shouldApplyReplacementsInDeclarationOrder() {
        CleanRules rules = new CleanRules();
        rules.getRegexReplacements().add(replacement("alpha", "beta"));
        rules.getRegexReplacements().add(replacement("beta", "gamma"));

        // The second rule sees the output of the first one, which is what declaration order means.
        assertEquals("gamma", cleaner.clean("alpha", List.of(), rules));
    }

    @Test
    void shouldSkipAnInvalidPatternWithoutFailingTheDocument() {
        CleanRules rules = new CleanRules();
        rules.setStripWatermarkPatterns(List.of("[unclosed"));
        rules.getRegexReplacements().add(replacement("(also broken", "x"));

        // One expression typed wrong by an operator must not make a knowledge base unindexable.
        assertEquals("body text", cleaner.clean("body text", List.of(), rules));
    }

    @Test
    void shouldTreatTheReplacementTextLiterally() {
        CleanRules rules = new CleanRules();
        rules.getRegexReplacements().add(replacement("total", "$100"));

        // A dollar sign in the replacement is a group reference to the regex engine, never to an operator.
        assertEquals("$100", cleaner.clean("total", List.of(), rules));
    }

    @Test
    void shouldNotApplyTheHeaderStepToAFragment() {
        CleanRules rules = new CleanRules();
        rules.setStripHeaderFooter(true);
        rules.getDesensitize().setEnabled(true);

        String cleaned = cleaner.cleanFragment("ACME Internal contact 13800138000", rules);

        // A fragment has no pages, so document level furniture cannot be judged; masking still applies.
        assertTrue(cleaned.contains("ACME Internal"));
        assertTrue(cleaned.contains("138****8000"));
    }

    @Test
    void shouldReturnTheFragmentUnchangedWhenNoRuleIsActive() {
        String fragment = "a chart showing 13800138000";
        assertSame(fragment, cleaner.cleanFragment(fragment, CleanRules.defaults()));
    }

    private CleanRules.RegexReplacement replacement(String pattern, String value) {
        CleanRules.RegexReplacement replacement = new CleanRules.RegexReplacement();
        replacement.setPattern(pattern);
        replacement.setReplacement(value);
        return replacement;
    }

    private ParsedDocument.ParsedPage page(int pageNo, String text) {
        return ParsedDocument.ParsedPage.builder().pageNo(pageNo).text(text).build();
    }
}
