package io.kbrag.domain.enums;

/**
 * Operator verdict on one debug page retrieval result, requirement section 4.5.
 *
 * @author owlzhangfq@gmail.com
 */
public enum FeedbackVerdict {

    /** The chunk answered the query, candidate evidence for an evaluation case. */
    GOOD,

    /** The chunk did not answer the query; feeds the miss statistics, never a case. */
    BAD;

    /**
     * Resolves a verdict from its request literal, case insensitively.
     *
     * @param value request literal, {@code null} or blank yields {@code null}
     * @return matching verdict, {@code null} when nothing matches
     */
    public static FeedbackVerdict from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (FeedbackVerdict verdict : values()) {
            if (verdict.name().equalsIgnoreCase(value.trim())) {
                return verdict;
            }
        }
        return null;
    }
}
