package io.kbrag.domain.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the precise removal of a disabled child from its parent text, requirement section 4.5: that
 * several passages come out at the right places, that one unknown offset makes the whole parent fall back
 * to being returned intact, and that the reported count matches what was removed.
 *
 * @author owlzhangfq@gmail.com
 */
class ParentTextRedactorTest {

    private static final String MARK = ParentTextRedactor.REDACTION_MARK;

    private final ParentTextRedactor redactor = new ParentTextRedactor();

    @Test
    void shouldRemoveASinglePassageAndLeaveTheMarkBehind() {
        // 0123456789
        ParentTextRedactor.Redaction redaction =
                redactor.redact("甲乙丙丁戊己庚辛", List.of(span(2, 5)));

        assertEquals("甲乙" + MARK + "己庚辛", redaction.text());
        assertEquals(1, redaction.redactedChildCount());
        assertTrue(redaction.applied());
    }

    @Test
    void shouldRemoveSeveralPassagesWithoutShiftingTheRemainingOffsets() {
        // Passed in ascending order on purpose: the descending walk is the implementation's job, and a
        // caller that had to sort first would eventually forget to.
        ParentTextRedactor.Redaction redaction =
                redactor.redact("甲乙丙丁戊己庚辛壬癸", List.of(span(1, 3), span(6, 9)));

        // Removing the second passage first is what keeps [1,3) still pointing at 乙丙 when its turn comes.
        assertEquals("甲" + MARK + "丁戊己" + MARK + "癸", redaction.text());
        assertEquals(2, redaction.redactedChildCount());
    }

    @Test
    void shouldClampOverlappingSpansInsteadOfCorruptingTheText() {
        // Children overlap by design - that is what child_overlap is - so two disabled neighbours can
        // describe intersecting slices.
        ParentTextRedactor.Redaction redaction =
                redactor.redact("甲乙丙丁戊己庚辛", List.of(span(1, 4), span(3, 6)));

        assertEquals("甲" + MARK + MARK + "庚辛", redaction.text());
        assertEquals(2, redaction.redactedChildCount());
    }

    @Test
    void shouldReturnTheWholeParentWhenOneOffsetIsUnknown() {
        String parent = "甲乙丙丁戊己庚辛";

        ParentTextRedactor.Redaction redaction =
                redactor.redact(parent, List.of(span(1, 3), new ParentTextRedactor.Span(null, null)));

        // A half redacted parent reads as a complete passage while silently missing a section, which is
        // worse than returning the excluded sentence together with its disabled_child_ids marker.
        assertSame(parent, redaction.text());
        assertEquals(0, redaction.redactedChildCount());
        assertFalse(redaction.applied());
    }

    @Test
    void shouldReturnTheWholeParentWhenAnOffsetReachesPastItsEnd() {
        String parent = "甲乙丙丁戊己庚辛";

        ParentTextRedactor.Redaction redaction = redactor.redact(parent, List.of(span(4, 99)));

        assertSame(parent, redaction.text());
        assertEquals(0, redaction.redactedChildCount());
    }

    @Test
    void shouldReturnTheWholeParentWhenAnOffsetPairIsEmptyOrInverted() {
        String parent = "甲乙丙丁戊己庚辛";

        assertEquals(0, redactor.redact(parent, List.of(span(3, 3))).redactedChildCount());
        assertEquals(0, redactor.redact(parent, List.of(span(5, 2))).redactedChildCount());
        assertEquals(0, redactor.redact(parent, List.of(span(-1, 2))).redactedChildCount());
    }

    @Test
    void shouldLeaveAParentWithoutDisabledChildrenAlone() {
        String parent = "甲乙丙丁戊己庚辛";

        assertSame(parent, redactor.redact(parent, List.of()).text());
        assertSame(parent, redactor.redact(parent, null).text());
    }

    private ParentTextRedactor.Span span(int start, int end) {
        return new ParentTextRedactor.Span(start, end);
    }
}
