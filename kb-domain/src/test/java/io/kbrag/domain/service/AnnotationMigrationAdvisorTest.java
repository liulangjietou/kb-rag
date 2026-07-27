package io.kbrag.domain.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the cross version migration advisor of requirement section 4.5.
 *
 * <p>The symmetry case is the important one. Requirement section 4.6 measures an overlap with the expected
 * span as the denominator, which is asymmetric by design and was explicitly ruled out here: with it, a
 * short annotation excerpt would score a perfect match against every long chunk containing it and the
 * recommendation would always point at the largest chunk of the version.
 *
 * @author owlzhangfq@gmail.com
 */
class AnnotationMigrationAdvisorTest {

    private static final double MIN_SCORE = 0.35d;

    /** Seventeen characters, comfortably above the short text floor. */
    private static final String SOURCE = "知识库检索需要把文档切成合适的片段";

    private final AnnotationMigrationAdvisor advisor =
            new AnnotationMigrationAdvisor(new ChunkTextHasher());

    @Test
    void shouldComputeTheDiceCoefficientOverCharacterTrigrams() {
        // "abcd" -> {abc, bcd}; "abce" -> {abc, bce}; one shared gram.
        // Dice = 2 * 1 / (2 + 2) = 0.5, computed by hand and asserted exactly.
        assertEquals(0.5d, advisor.similarity("abcd", "abce"));
    }

    @Test
    void shouldScoreIdenticalTextsAsAPerfectMatch() {
        // The exact inheritance already carried this case, so it must never look like a partial match.
        assertEquals(1.0d, advisor.similarity(SOURCE, SOURCE));
    }

    @Test
    void shouldScoreTextsWithoutASharedTrigramAsZero() {
        assertEquals(0.0d, advisor.similarity("abcd", "wxyz"));
    }

    @Test
    void shouldBeSymmetric() {
        String left = "知识库检索需要把文档切成合适的片段";
        String right = "知识库检索需要把文档切成大小合适的片段";

        assertEquals(advisor.similarity(left, right), advisor.similarity(right, left));
        assertTrue(advisor.similarity(left, right) > 0.0d);
    }

    @Test
    void shouldIgnoreWhitespaceAndFullwidthDifferencesLikeTheTextHashDoes() {
        assertEquals(1.0d, advisor.similarity("abcdefghij", "ａｂｃｄ efghij"));
    }

    @Test
    void shouldGiveNoCandidateForAShortAnnotationText() {
        // Nine normalised characters: below three grams' worth of signal the coefficient is noise wearing
        // the costume of a number, so the advisor stays silent instead.
        List<AnnotationMigrationAdvisor.Suggestion> suggestions =
                advisor.suggest("知识库检索需要把文", List.of(candidate("ck_1", "知识库检索需要把文")), MIN_SCORE);

        assertEquals(List.of(), suggestions);
    }

    @Test
    void shouldDropCandidatesBelowTheThreshold() {
        List<AnnotationMigrationAdvisor.Suggestion> suggestions = advisor.suggest(SOURCE, List.of(
                candidate("ck_same", SOURCE),
                candidate("ck_other", "应用中心的版本发布需要冻结检索配置")), MIN_SCORE);

        assertEquals(List.of("ck_same"), suggestions.stream()
                .map(AnnotationMigrationAdvisor.Suggestion::chunkId).toList());
        assertEquals(1.0d, suggestions.get(0).score());
    }

    @Test
    void shouldKeepOnlyTheThreeBestCandidates() {
        List<AnnotationMigrationAdvisor.Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            candidates.add(candidate("ck_" + i, SOURCE));
        }

        List<AnnotationMigrationAdvisor.Suggestion> suggestions =
                advisor.suggest(SOURCE, candidates, MIN_SCORE);

        // Equal scores are broken on the chunk id, so two refreshes of the review list never reshuffle.
        assertEquals(List.of("ck_0", "ck_1", "ck_2"), suggestions.stream()
                .map(AnnotationMigrationAdvisor.Suggestion::chunkId).toList());
    }

    @Test
    void shouldOrderCandidatesByDescendingScore() {
        List<AnnotationMigrationAdvisor.Suggestion> suggestions = advisor.suggest(SOURCE, List.of(
                candidate("ck_edited", "知识库检索需要把文档切成大小合适的片段"),
                candidate("ck_same", SOURCE)), MIN_SCORE);

        assertEquals(List.of("ck_same", "ck_edited"), suggestions.stream()
                .map(AnnotationMigrationAdvisor.Suggestion::chunkId).toList());
        assertTrue(suggestions.get(0).score() > suggestions.get(1).score());
    }

    @Test
    void shouldCapTheContentPreviewAtOneHundredAndTwentyCharacters() {
        String long_ = SOURCE.repeat(20);

        List<AnnotationMigrationAdvisor.Suggestion> suggestions =
                advisor.suggest(SOURCE, List.of(candidate("ck_1", long_)), MIN_SCORE);

        assertEquals(120, suggestions.get(0).contentPreview().length());
        assertEquals(long_.substring(0, 120), suggestions.get(0).contentPreview());
    }

    @Test
    void shouldGiveNoCandidateWhenTheVersionHasNoChunk() {
        assertEquals(List.of(), advisor.suggest(SOURCE, List.of(), MIN_SCORE));
        assertEquals(List.of(), advisor.suggest(SOURCE, null, MIN_SCORE));
        assertEquals(List.of(), advisor.suggest(null, List.of(candidate("ck_1", SOURCE)), MIN_SCORE));
    }

    private AnnotationMigrationAdvisor.Candidate candidate(String chunkId, String content) {
        return new AnnotationMigrationAdvisor.Candidate(chunkId, content);
    }
}
