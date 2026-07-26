package io.kbrag.domain.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Cuts a query into the terms the graph route matches against the entity name index,
 * requirement section 4.9 "the query is tokenised, no model call is involved".
 *
 * <p><b>Why the tokenizer is this simple.</b> The entity name index is analysed by the graph engine
 * itself - a CJK analyser that produces bigrams for Chinese and lower cased words for Latin - so the job
 * here is not to segment Chinese, it is to hand the engine the runs of text worth analysing and to drop
 * everything that could not name an entity. Re-implementing segmentation on top of an analyser that
 * already does it would produce two different notions of a term, and the one used at write time is the
 * one that decides what can ever match.
 *
 * <p>Latin runs shorter than two characters are dropped: a single letter matches a large share of any
 * corpus and would spend the whole match budget on noise. Chinese runs are kept whole because the
 * analyser turns them into overlapping bigrams, which is exactly the recall a dictionary free deployment
 * can get.
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class GraphQueryTokenizer {

    /** Shortest Latin run that may become a term. */
    private static final int MIN_LATIN_TERM_LENGTH = 2;

    /** Longest run kept as one term, so a pasted paragraph cannot become a single huge clause. */
    private static final int MAX_TERM_LENGTH = 64;

    /** Terms one query may contribute, bounding the graph side query regardless of the input length. */
    private static final int MAX_TERMS = 32;

    /**
     * Tokenises a query.
     *
     * @param query raw or rewritten query, {@code null} and blank both yield no term
     * @return distinct terms in the order they appear, at most {@value #MAX_TERMS}
     */
    public List<String> tokenize(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        Set<String> terms = new LinkedHashSet<>();
        StringBuilder run = new StringBuilder();
        boolean runIsCjk = false;
        for (int index = 0; index < query.length(); index++) {
            char character = query.charAt(index);
            if (!Character.isLetterOrDigit(character)) {
                flush(run, runIsCjk, terms);
                continue;
            }
            boolean cjk = isCjk(character);
            if (run.length() > 0 && cjk != runIsCjk) {
                // A script boundary ends the run: "Apple苹果" is two terms, not one that neither
                // analyser branch could handle.
                flush(run, runIsCjk, terms);
            }
            runIsCjk = cjk;
            run.append(character);
        }
        flush(run, runIsCjk, terms);
        return terms.size() <= MAX_TERMS ? List.copyOf(terms)
                : List.copyOf(new ArrayList<>(terms).subList(0, MAX_TERMS));
    }

    /**
     * Turns the accumulated run into a term and resets it.
     *
     * @param run   accumulated characters, emptied by this call
     * @param isCjk {@code true} when the run is made of ideographs
     * @param terms collected terms
     */
    private void flush(StringBuilder run, boolean isCjk, Set<String> terms) {
        if (run.length() == 0) {
            return;
        }
        String value = run.toString();
        run.setLength(0);
        if (value.length() > MAX_TERM_LENGTH) {
            value = value.substring(0, MAX_TERM_LENGTH);
        }
        if (isCjk) {
            terms.add(value);
            return;
        }
        if (value.length() >= MIN_LATIN_TERM_LENGTH) {
            terms.add(value.toLowerCase());
        }
    }

    /**
     * Tells whether a character belongs to a script the analyser treats as ideographic.
     *
     * @param character character under test
     * @return {@code true} for Han, Hiragana, Katakana and Hangul
     */
    private boolean isCjk(char character) {
        Character.UnicodeScript script = Character.UnicodeScript.of(character);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }
}
