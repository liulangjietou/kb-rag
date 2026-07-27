package io.kbrag.domain.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the hit judgment algorithm of requirement section 4.6: span denominator, full containment,
 * normalisation (whitespace, full/half width folding, mask character), and the aggregate coverage
 * union across several candidates.
 *
 * @author owlzhangfq@gmail.com
 */
class OverlapRatioCalculatorTest {

    private final OverlapRatioCalculator calculator = new OverlapRatioCalculator(new ChunkTextHasher());

    @Test
    void shouldScoreOneWhenTheCandidateFullyContainsTheSpan() {
        String span = "hello world";
        String candidate = "say hello world today";

        // Ratio is fixed on the span as the denominator: a longer candidate that fully contains the
        // span is never penalised for carrying more context than the span needs.
        assertEquals(1.0d, calculator.overlapRatio(candidate, span));
    }

    @Test
    void shouldIgnoreWhitespaceDifferences() {
        String span = "hello world";
        String candidate = "hello \n  world";

        assertEquals(1.0d, calculator.overlapRatio(candidate, span));
    }

    @Test
    void shouldFoldFullWidthCharactersOntoHalfWidth() {
        String span = "ABC123";
        String candidate = "ＡＢＣ１２３";

        assertEquals(1.0d, calculator.overlapRatio(candidate, span));
    }

    @Test
    void shouldIgnoreTheDesensitizationMaskCharacterRegardlessOfItsWidth() {
        // Same phone number, masked with two different rule widths - the span keeps the older, wider
        // mask while the chunk was re-produced with a narrower one. Stripping every mask character
        // from both sides before comparing is what makes the two still match.
        String span = "客户手机138****5678已确认";
        String candidate = "客户手机138**5678已确认";

        assertEquals(1.0d, calculator.overlapRatio(candidate, span));
    }

    @Test
    void shouldComputeAPartialRatioProportionalToTheCoveredCharacters() {
        // Span is 10 characters after normalisation; the candidate only supplies the first half.
        String span = "ABCDEFGHIJ";
        String candidate = "ABCDE";

        assertEquals(0.5d, calculator.overlapRatio(candidate, span));
    }

    @Test
    void shouldScoreZeroWhenNothingIsShared() {
        assertEquals(0.0d, calculator.overlapRatio("xyz", "ABCDEFGHIJ"));
    }

    @Test
    void shouldUnionTwoAdjacentPartialCoveragesIntoAFullHit() {
        String span = "ABCDEFGHIJ";
        String first = "ABCDE";
        String second = "FGHIJ";

        // Neither candidate alone reaches a 0.6 threshold ...
        assertEquals(0.5d, calculator.overlapRatio(first, span));
        assertEquals(0.5d, calculator.overlapRatio(second, span));
        assertTrue(calculator.overlapRatio(first, span) < 0.6d);

        // ... but their union covers the whole span, which is exactly the aggregate coverage judgment.
        double aggregate = calculator.aggregateCoverage(List.of(first, second), span);
        assertEquals(1.0d, aggregate);
        assertTrue(calculator.isHit(List.of(first, second), span, 0.6d));
        assertTrue(!calculator.isHit(List.of(first), span, 0.6d));
    }

    @Test
    void shouldNotDoubleCountAnOverlappingRegionCoveredByTwoCandidates() {
        String span = "ABCDEFGHIJ";
        // Both candidates cover the same first half; the union must still be 0.5, not 1.0.
        double aggregate = calculator.aggregateCoverage(List.of("ABCDE", "ABCDE"), span);

        assertEquals(0.5d, aggregate);
    }

    @Test
    void shouldReportZeroCoverageForAnEmptyCandidateList() {
        assertEquals(0.0d, calculator.aggregateCoverage(List.of(), "ABCDEFGHIJ"));
    }
}
