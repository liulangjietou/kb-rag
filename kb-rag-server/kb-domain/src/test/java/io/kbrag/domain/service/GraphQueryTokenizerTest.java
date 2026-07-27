package io.kbrag.domain.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the query side tokenisation of the graph route, Chinese included: the script boundary, the
 * punctuation split, the lower casing of Latin runs, the single letter rule and the term budget.
 *
 * @author owlzhangfq@gmail.com
 */
class GraphQueryTokenizerTest {

    private final GraphQueryTokenizer tokenizer = new GraphQueryTokenizer();

    @Test
    void shouldKeepAChineseRunAsOneTerm() {
        assertEquals(List.of("苹果公司"), tokenizer.tokenize("苹果公司"));
    }

    @Test
    void shouldSplitAChineseQueryOnPunctuation() {
        // A run of ideographs stays whole, particles included: segmenting Chinese here would create a
        // second notion of a term next to the analyser's, and the analyser's is the one that decides what
        // was indexed. Punctuation is the only boundary this side knows about.
        assertEquals(List.of("苹果公司的创始人", "是谁"),
                tokenizer.tokenize("苹果公司的创始人，是谁？"));
    }

    @Test
    void shouldSplitOnTheBoundaryBetweenTwoScripts() {
        assertEquals(List.of("apple", "苹果", "m2"), tokenizer.tokenize("Apple苹果 M2"));
    }

    @Test
    void shouldLowerCaseLatinTermsAndDropSingleLetters() {
        assertEquals(List.of("openai", "gpt"), tokenizer.tokenize("OpenAI a GPT"));
    }

    @Test
    void shouldKeepASingleIdeographBecauseItCanBeAWholeName() {
        assertEquals(List.of("周"), tokenizer.tokenize("周"));
    }

    @Test
    void shouldDeduplicateRepeatedTermsKeepingTheFirstPosition() {
        assertEquals(List.of("苹果", "公司"), tokenizer.tokenize("苹果 公司 苹果"));
    }

    @Test
    void shouldYieldNothingForABlankOrPunctuationOnlyQuery() {
        assertTrue(tokenizer.tokenize(null).isEmpty());
        assertTrue(tokenizer.tokenize("   ").isEmpty());
        assertTrue(tokenizer.tokenize("???!!!").isEmpty());
    }

    @Test
    void shouldCapTheNumberOfTerms() {
        StringBuilder query = new StringBuilder();
        for (int index = 0; index < 60; index++) {
            query.append("term").append(index).append(' ');
        }

        assertEquals(32, tokenizer.tokenize(query.toString()).size());
    }

    @Test
    void shouldTruncateAnOverlongRun() {
        String run = "a".repeat(200);

        List<String> terms = tokenizer.tokenize(run);

        assertEquals(1, terms.size());
        assertEquals(64, terms.get(0).length());
    }
}
