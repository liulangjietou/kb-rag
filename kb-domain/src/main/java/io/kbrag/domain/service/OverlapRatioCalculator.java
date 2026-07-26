package io.kbrag.domain.service;

import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Span level hit judgment algorithm of the evaluation subsystem, requirement section 4.6.
 *
 * <p><b>Overlap ratio.</b> {@code overlapRatio(candidate, span) = normalised character intersection
 * length / normalised span length}, span fixed as the denominator so a chunk that fully contains the
 * span always scores {@code 1.0} regardless of how much longer the chunk is - a long parent chunk is
 * never penalised for carrying more context than the span needs. Candidate and span are both excerpts
 * of the same source document, so any genuine overlap between them is one contiguous run of
 * characters; the "intersection" is therefore computed as the longest common substring rather than as
 * a bag of characters, which would count a shared character bag as an overlap even between two
 * completely unrelated sentences.
 *
 * <p><b>Normalisation</b> reuses {@link ChunkTextHasher#normalize} for the NFKC fold and the
 * whitespace strip already vetted for cross version chunk matching, and additionally drops the
 * desensitisation mask character so a masked phone number in a chunk still matches an unmasked one
 * quoted in a span, and vice versa.
 *
 * <p><b>Aggregate coverage.</b> A span can straddle a splitter boundary, landing partly in one
 * returned chunk and partly in its neighbour; scoring each chunk against the whole span alone would
 * then systematically under-count a document that was, from the caller's point of view, fully
 * answered by two adjacent chunks. {@link #aggregateCoverage} instead finds the best matching run of
 * every candidate, merges their positions inside the span into a single covered length, and reports
 * that union over the span length - so two chunks that together cover the whole span score {@code
 * 1.0} even though neither one does alone.
 *
 * @author owlzhangfq@gmail.com
 */
@Component
@RequiredArgsConstructor
public class OverlapRatioCalculator {

    /** Desensitisation mask character, requirement section 4.6 "ignore the mask character". */
    private static final char MASK_CHAR = '*';

    private final ChunkTextHasher chunkTextHasher;

    /**
     * Normalises a text for overlap comparison: NFKC fold, whitespace stripped, mask character
     * dropped.
     *
     * @param text raw text, {@code null} treated as empty
     * @return normalised text
     */
    public String normalize(String text) {
        String base = chunkTextHasher.normalize(text);
        if (base.indexOf(MASK_CHAR) < 0) {
            return base;
        }
        StringBuilder builder = new StringBuilder(base.length());
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            if (c != MASK_CHAR) {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    /**
     * Pairwise overlap ratio of one candidate text against one span.
     *
     * @param candidateText recalled chunk (or child chunk) text, raw
     * @param span          evidence span, raw
     * @return ratio in {@code [0,1]}, {@code 0} when the span normalises to nothing
     */
    public double overlapRatio(String candidateText, String span) {
        String normSpan = normalize(span);
        if (normSpan.isEmpty()) {
            return 0.0d;
        }
        String normCandidate = normalize(candidateText);
        int overlap = longestCommonRun(normSpan, normCandidate).length();
        return Math.min(1.0d, (double) overlap / normSpan.length());
    }

    /**
     * Aggregate coverage of a span by a set of candidate texts, requirement section 4.6 "aggregate
     * coverage judgment".
     *
     * <p>When parent child splitting is on, the caller passes the child chunk texts of the returned
     * parent units rather than the parent texts themselves, so the covered set matches what the top K
     * actually returned at the granularity retrieval scored.
     *
     * @param candidateTexts recalled texts, one per contributing chunk
     * @param span           evidence span, raw
     * @return union coverage ratio in {@code [0,1]}
     */
    public double aggregateCoverage(List<String> candidateTexts, String span) {
        String normSpan = normalize(span);
        if (normSpan.isEmpty() || CollectionUtils.isEmpty(candidateTexts)) {
            return 0.0d;
        }
        List<Run> runs = new ArrayList<>(candidateTexts.size());
        for (String candidate : candidateTexts) {
            if (candidate == null || candidate.isEmpty()) {
                continue;
            }
            Run run = longestCommonRun(normSpan, normalize(candidate));
            if (run.length() > 0) {
                runs.add(run);
            }
        }
        return (double) unionLength(runs) / normSpan.length();
    }

    /**
     * Tells whether a span counts as hit given the top K candidate texts.
     *
     * @param candidateTexts recalled texts
     * @param span           evidence span
     * @param threshold      aggregate coverage threshold, {@code kb.eval.overlap-threshold}
     * @return {@code true} when the aggregate coverage reaches the threshold
     */
    public boolean isHit(List<String> candidateTexts, String span, double threshold) {
        return aggregateCoverage(candidateTexts, span) >= threshold;
    }

    /**
     * Finds the longest contiguous run shared by two normalised texts and its position inside
     * {@code a}.
     *
     * <p>Classic dynamic programming, one row of the match length table kept at a time: {@code
     * table[j]} is the length of the common suffix ending at {@code a[i-1]} and {@code b[j-1]}. Ties
     * keep the first maximal run encountered in reading order of {@code a}, which is an accepted
     * simplification - two disjoint equally long matches inside one span are not expected between a
     * span and a single chunk excerpt of the same source document.
     *
     * @param a normalised span text, the position is reported against this string
     * @param b normalised candidate text
     * @return best run, {@link Run#length()} {@code 0} when the two texts share nothing
     */
    private Run longestCommonRun(String a, String b) {
        int n = a.length();
        int m = b.length();
        if (n == 0 || m == 0) {
            return new Run(0, 0);
        }
        int[] previous = new int[m + 1];
        int[] current = new int[m + 1];
        int bestLength = 0;
        int bestEnd = 0;
        for (int i = 1; i <= n; i++) {
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                if (ca == b.charAt(j - 1)) {
                    current[j] = previous[j - 1] + 1;
                    if (current[j] > bestLength) {
                        bestLength = current[j];
                        bestEnd = i;
                    }
                } else {
                    current[j] = 0;
                }
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return new Run(bestEnd - bestLength, bestEnd);
    }

    /**
     * Merges overlapping or adjacent runs and sums their total covered length.
     *
     * @param runs candidate runs, position inside the span
     * @return total length covered by the union of the runs
     */
    private int unionLength(List<Run> runs) {
        if (runs.isEmpty()) {
            return 0;
        }
        List<Run> ordered = new ArrayList<>(runs);
        ordered.sort(Comparator.comparingInt(Run::start));
        int total = 0;
        int currentStart = ordered.get(0).start();
        int currentEnd = ordered.get(0).end();
        for (int i = 1; i < ordered.size(); i++) {
            Run run = ordered.get(i);
            if (run.start() > currentEnd) {
                total += currentEnd - currentStart;
                currentStart = run.start();
                currentEnd = run.end();
            } else {
                currentEnd = Math.max(currentEnd, run.end());
            }
        }
        total += currentEnd - currentStart;
        return total;
    }

    /**
     * Half open character range {@code [start,end)} inside the span that one candidate text matched.
     *
     * @param start inclusive start position
     * @param end   exclusive end position
     */
    private record Run(int start, int end) {

        int length() {
            return end - start;
        }
    }
}
