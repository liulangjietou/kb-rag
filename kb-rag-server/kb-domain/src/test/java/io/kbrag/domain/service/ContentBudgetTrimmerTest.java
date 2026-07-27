package io.kbrag.domain.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins down the {@code max_content_length} rule of requirement section 4.8: whole ranked units are kept or
 * dropped, the lowest ranked ones go first, and a single oversized first unit is still returned.
 *
 * @author owlzhangfq@gmail.com
 */
class ContentBudgetTrimmerTest {

    private final ContentBudgetTrimmer trimmer = new ContentBudgetTrimmer();

    @Test
    void shouldKeepEveryUnitThatFitsTheBudget() {
        assertEquals(3, trimmer.keepCount(List.of(100, 100, 100), 300));
    }

    @Test
    void shouldDropTheLowestRankedUnitsThatExceedTheBudget() {
        assertEquals(2, trimmer.keepCount(List.of(100, 100, 100), 250));
    }

    @Test
    void shouldNeverCutAUnitInHalf() {
        // A budget of 150 fits one 100 character unit; the second would take the total to 200, so it is
        // dropped whole rather than truncated to 50 characters.
        assertEquals(1, trimmer.keepCount(List.of(100, 100), 150));
    }

    @Test
    void shouldKeepTheFirstUnitEvenWhenItAloneExceedsTheBudget() {
        // Returning nothing would hide the answer instead of trimming the response.
        assertEquals(1, trimmer.keepCount(List.of(500, 10), 100));
    }

    @Test
    void shouldTreatANullOrNonPositiveBudgetAsNoTrimming() {
        assertEquals(3, trimmer.keepCount(List.of(1000, 1000, 1000), null));
        assertEquals(3, trimmer.keepCount(List.of(1000, 1000, 1000), 0));
        assertEquals(3, trimmer.keepCount(List.of(1000, 1000, 1000), -5));
    }

    @Test
    void shouldReturnZeroForAnEmptyList() {
        assertEquals(0, trimmer.keepCount(List.of(), 100));
        assertEquals(0, trimmer.keepCount(null, 100));
    }

    @Test
    void shouldTolerateANullLength() {
        assertEquals(2, trimmer.keepCount(java.util.Arrays.asList(null, 50), 50));
    }
}
