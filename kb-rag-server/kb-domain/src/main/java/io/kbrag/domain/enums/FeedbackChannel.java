package io.kbrag.domain.enums;

/**
 * Boundary a retrieval feedback arrived through.
 *
 * <p>One dimension, not two tables: an end user's verdict through the open API and an operator's
 * verdict on the debug page are the same observation about the same chunk, and the insight report
 * wants them in one place. What differs is trust - a console row names an authenticated operator,
 * an open API row carries a caller-asserted end user id the platform does not vouch for - and that
 * difference is exactly what this column lets the report filter on.
 *
 * @author owlzhangfq@gmail.com
 */
public enum FeedbackChannel {

    /** Submitted by an authenticated operator on the console debug page. */
    CONSOLE,

    /** Submitted through the open API under an API key. */
    OPEN_API;

    /**
     * Parses a submitted value, tolerant of case.
     *
     * @param value submitted channel name
     * @return matching channel, or {@code null} when the value names none
     */
    public static FeedbackChannel from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (FeedbackChannel channel : values()) {
            if (channel.name().equalsIgnoreCase(value.trim())) {
                return channel;
            }
        }
        return null;
    }
}
