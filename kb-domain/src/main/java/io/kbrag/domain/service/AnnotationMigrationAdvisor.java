package io.kbrag.domain.service;

import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Recommends the chunk of a newly activated version an older annotation could be moved to,
 * requirement section 4.5.
 *
 * <p><b>The similarity has to be symmetric, and that is a hard rule.</b> Requirement section 4.6 measures
 * the overlap of an evaluation span against a retrieved chunk with the span as the denominator, which is
 * correct there - the question is "how much of the expected evidence was returned" - and wrong here: a
 * short annotation excerpt would score a perfect match against every long chunk that happens to contain
 * it, so the recommendation would always point at the largest chunk in the version. The Dice coefficient
 * over character 3-grams is used instead: {@code 2 * |A n B| / (|A| + |B|)} is symmetric by construction
 * and penalises a length mismatch, so {@code similarity(a, b) == similarity(b, a)} always holds.
 *
 * <p><b>Only a recommendation.</b> Nothing here moves an annotation; the numbers are shown next to a
 * confirmation button, because a wrong automatic migration re-disables or rewrites a passage nobody
 * reviewed, which costs far more than the seconds a human confirmation takes.
 *
 * <p>Normalisation is the one used by {@link ChunkTextHasher}, so a chunk that the exact inheritance
 * already matched scores exactly {@code 1.0} here and never looks like a partial match.
 *
 * @author owlzhangfq@gmail.com
 */
@Component
@RequiredArgsConstructor
public class AnnotationMigrationAdvisor {

    /** Size of the character n-grams the coefficient is computed on. */
    public static final int GRAM_SIZE = 3;

    /**
     * Shortest normalised text that still yields a meaningful score.
     *
     * <p>Below three grams the coefficient degenerates: "第一条" against "第一章" shares no gram at all and
     * scores zero, while "同意" produces no gram whatsoever. Reporting a candidate for such a text would be
     * noise dressed up as a number, so the advisor stays silent instead.
     */
    public static final int MIN_TEXT_LENGTH = 10;

    /** Recommendations one review row carries; three fits a confirmation dialog and bounds the noise. */
    private static final int MAX_SUGGESTIONS = 3;

    /** Longest preview shown next to a recommendation. */
    private static final int PREVIEW_MAX_LENGTH = 120;

    private final ChunkTextHasher chunkTextHasher;

    /**
     * Ranks the candidates of a version against the text an annotation was made on.
     *
     * @param sourceText annotated text of the older version, {@code null} tolerated
     * @param candidates chunks of the newly activated version
     * @param minScore   lowest coefficient a recommendation must reach
     * @return at most three recommendations, best first, empty when the text is too short to judge
     */
    public List<Suggestion> suggest(String sourceText, List<Candidate> candidates, double minScore) {
        String source = chunkTextHasher.normalize(sourceText);
        if (source.length() < MIN_TEXT_LENGTH || CollectionUtils.isEmpty(candidates)) {
            return List.of();
        }
        Set<String> sourceGrams = grams(source);
        List<Suggestion> scored = new ArrayList<>(candidates.size());
        for (Candidate candidate : candidates) {
            double score = dice(sourceGrams, grams(chunkTextHasher.normalize(candidate.content())));
            if (score < minScore) {
                continue;
            }
            scored.add(new Suggestion(candidate.chunkId(), preview(candidate.content()), score));
        }
        // Tie broken on the chunk id so two equally similar candidates always come back in the same
        // order: a recommendation list that reshuffles between two refreshes cannot be trusted.
        scored.sort(Comparator.comparingDouble(Suggestion::score).reversed()
                .thenComparing(Suggestion::chunkId));
        return scored.size() <= MAX_SUGGESTIONS ? scored : new ArrayList<>(scored.subList(0, MAX_SUGGESTIONS));
    }

    /**
     * Dice coefficient of two texts, the symmetric measure the whole feature is built on.
     *
     * @param left  one text, {@code null} treated as empty
     * @param right the other text, {@code null} treated as empty
     * @return coefficient between {@code 0.0} and {@code 1.0}
     */
    public double similarity(String left, String right) {
        return dice(grams(chunkTextHasher.normalize(left)), grams(chunkTextHasher.normalize(right)));
    }

    /**
     * Distinct character n-grams of a normalised text.
     *
     * @param normalized normalised text
     * @return gram set, empty when the text is shorter than one gram
     */
    private Set<String> grams(String normalized) {
        if (normalized == null || normalized.length() < GRAM_SIZE) {
            return Set.of();
        }
        Set<String> grams = new HashSet<>(normalized.length());
        for (int i = 0; i + GRAM_SIZE <= normalized.length(); i++) {
            grams.add(normalized.substring(i, i + GRAM_SIZE));
        }
        return grams;
    }

    private double dice(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0.0d;
        }
        int shared = 0;
        for (String gram : left) {
            if (right.contains(gram)) {
                shared++;
            }
        }
        return (2.0d * shared) / (left.size() + right.size());
    }

    private String preview(String content) {
        if (content == null) {
            return null;
        }
        return content.length() <= PREVIEW_MAX_LENGTH ? content : content.substring(0, PREVIEW_MAX_LENGTH);
    }

    /**
     * One chunk of the newly activated version a migration could target.
     *
     * @param chunkId chunk business id
     * @param content chunk text before normalisation
     */
    public record Candidate(String chunkId, String content) {
    }

    /**
     * One recommendation shown next to a review row.
     *
     * @param chunkId        chunk the annotation could be moved to
     * @param contentPreview leading characters of that chunk, so an operator can recognise it
     * @param score          Dice coefficient between {@code 0.0} and {@code 1.0}
     */
    public record Suggestion(String chunkId, String contentPreview, double score) {
    }
}
