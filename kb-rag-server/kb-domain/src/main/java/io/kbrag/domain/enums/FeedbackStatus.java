package io.kbrag.domain.enums;

/**
 * Lifecycle of one feedback row: {@code NEW} is the only state an operator can act on, and both
 * outcomes are terminal - a converted feedback lives on as its evaluation case, a dismissed one is
 * kept only so the statistics stay honest.
 *
 * @author owlzhangfq@gmail.com
 */
public enum FeedbackStatus {

    /** Recorded and waiting for an operator decision. */
    NEW,

    /** Turned into an evaluation case, terminal. */
    CONVERTED,

    /** Judged not worth a case, terminal. */
    DISMISSED;

    /**
     * Resolves a status from its request literal, case insensitively.
     *
     * @param value request literal, {@code null} or blank yields {@code null}
     * @return matching status, {@code null} when nothing matches
     */
    public static FeedbackStatus from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (FeedbackStatus status : values()) {
            if (status.name().equalsIgnoreCase(value.trim())) {
                return status;
            }
        }
        return null;
    }
}
