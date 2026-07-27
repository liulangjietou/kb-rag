package io.kbrag.domain.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the token heuristic the splitter budgets against.
 *
 * @author owlzhangfq@gmail.com
 */
class SimpleTokenEstimatorTest {

    private final SimpleTokenEstimator estimator = new SimpleTokenEstimator();

    @Test
    void shouldReturnZeroForEmptyInput() {
        assertEquals(0, estimator.estimate(null));
        assertEquals(0, estimator.estimate(""));
    }

    @Test
    void shouldCountOneTokenPerCjkCharacter() {
        assertEquals(4, estimator.estimate("知识库检"));
    }

    @Test
    void shouldCountFourLatinCharactersAsOneToken() {
        assertEquals(2, estimator.estimate("abcdefgh"));
        // Nine characters need three tokens: the remainder is rounded up, never truncated.
        assertEquals(3, estimator.estimate("abcdefghi"));
    }

    @Test
    void shouldCombineCjkAndLatinCounts() {
        // Two CJK characters plus four latin characters equals two plus one tokens.
        assertEquals(3, estimator.estimate("知识abcd"));
    }

    @Test
    void shouldReturnPrefixLengthWithinBudget() {
        String text = "一二三四五";
        assertEquals(3, estimator.prefixLengthWithinBudget(text, 3));
        assertEquals(text.length(), estimator.prefixLengthWithinBudget(text, 10));
    }

    @Test
    void shouldKeepPrefixConsistentWithEstimate() {
        String text = "knowledge base retrieval augmented generation";
        int budget = 5;
        int cut = estimator.prefixLengthWithinBudget(text, budget);
        assertTrue(estimator.estimate(text.substring(0, cut)) <= budget);
        assertTrue(cut < text.length());
    }

    @Test
    void shouldRejectNonPositiveBudget() {
        assertThrows(IllegalArgumentException.class, () -> estimator.prefixLengthWithinBudget("abc", 0));
    }
}
