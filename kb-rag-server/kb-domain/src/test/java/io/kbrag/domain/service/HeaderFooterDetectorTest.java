package io.kbrag.domain.service;

import io.kbrag.domain.model.ParsedDocument;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the cross page detection: what counts as furniture, and what has to be left alone.
 *
 * @author owlzhangfq@gmail.com
 */
class HeaderFooterDetectorTest {

    private final HeaderFooterDetector detector = new HeaderFooterDetector();

    @Test
    void shouldDetectALineRepeatedAtTheTopOfEveryPage() {
        Set<String> furniture = detector.detect(List.of(
                page(1, "ACME Internal\nchapter one begins here\nmore body text"),
                page(2, "ACME Internal\nchapter one continues\nmore body text"),
                page(3, "ACME Internal\nchapter two begins\nmore body text")));

        assertTrue(furniture.contains("ACME Internal"));
        assertFalse(furniture.contains("chapter one begins here"));
    }

    @Test
    void shouldDetectAFooterWhosePageNumberChanges() {
        // The page number is the one part of a footer that differs on every page, so digits are dropped
        // before comparing; a raw equality check would never find a match here.
        Set<String> furniture = detector.detect(List.of(
                page(1, "body of page one\nPage 1 of 3"),
                page(2, "body of page two\nPage 2 of 3"),
                page(3, "body of page three\nPage 3 of 3")));

        assertTrue(furniture.contains(detector.normalise("Page 1 of 3")));
    }

    @Test
    void shouldIgnoreALineThatRepeatsInTheBodyRatherThanAtAnEdge() {
        // Only the outer two lines of a page are candidates, so the repeated sentence sits on the third line
        // of a six line page: far enough from both edges to be body text.
        Set<String> furniture = detector.detect(List.of(
                page(1, "top one\ntop two\nrepeated body sentence\nbody one\nbottom one\nbottom two"),
                page(2, "top three\ntop four\nrepeated body sentence\nbody two\nbottom three\nbottom four"),
                page(3, "top five\ntop six\nrepeated body sentence\nbody three\nbottom five\nbottom six")));

        // Position is part of the definition: a sentence recurring in the middle of a page is content.
        assertFalse(furniture.contains("repeated body sentence"));
        assertTrue(furniture.isEmpty(), "nothing repeats at an edge in this document");
    }

    @Test
    void shouldDetectNothingForASinglePage() {
        Set<String> furniture = detector.detect(List.of(page(1, "ACME Internal\nonly one page")));

        // One sample is no evidence, and guessing here would delete real content.
        assertTrue(furniture.isEmpty());
    }

    @Test
    void shouldDetectNothingWithoutPages() {
        assertTrue(detector.detect(List.of()).isEmpty());
        assertTrue(detector.detect(null).isEmpty());
    }

    @Test
    void shouldRequireTheConfiguredShareOfPages() {
        List<ParsedDocument.ParsedPage> pages = new ArrayList<>();
        pages.add(page(1, "ACME Internal\nbody one"));
        pages.add(page(2, "ACME Internal\nbody two"));
        pages.add(page(3, "other top line\nbody three"));
        pages.add(page(4, "other top line\nbody four"));
        pages.add(page(5, "other top line\nbody five"));

        // Two of five pages is 40 percent, below the default 60 percent share.
        assertFalse(detector.detect(pages).contains("ACME Internal"));
        // Lowering the bar to 40 percent lets both candidates through.
        assertTrue(detector.detect(pages, 0.4d).contains("ACME Internal"));
    }

    @Test
    void shouldIgnoreALongLineAtAPageEdge() {
        String longLine = "x".repeat(200);
        Set<String> furniture = detector.detect(List.of(
                page(1, longLine + "\nbody one"),
                page(2, longLine + "\nbody two")));

        // A 200 character line is a paragraph that happens to start a page, not a running header.
        assertFalse(furniture.contains(longLine));
    }

    @Test
    void shouldNormaliseWhitespaceAndDigits() {
        assertEquals("Page of", detector.normalise("  Page   1 of  3  "));
        assertEquals("", detector.normalise(null));
    }

    private ParsedDocument.ParsedPage page(int pageNo, String text) {
        return ParsedDocument.ParsedPage.builder().pageNo(pageNo).text(text).build();
    }
}
