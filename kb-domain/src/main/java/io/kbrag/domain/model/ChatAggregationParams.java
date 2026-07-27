package io.kbrag.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Aggregation window of a chat import, stored inside {@code index_config.chat_aggregation}.
 *
 * <p>A window closes on whichever bound is reached first, elapsed time or message count.
 *
 * <p><b>Overlap is opt in and stays small.</b> The sequential cut remains the default because a
 * conversation is already a sequence of short turns, so a wide overlap would replay most messages in two
 * chunks and inflate the index for no recall gain. What overlap buys is the boundary case the sequential
 * cut loses: a question and its answer landing either side of a cut. The ceiling is therefore half the
 * message bound, which keeps every message in at most two windows.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatAggregationParams {

    /** Default span of one window in minutes. */
    public static final int DEFAULT_WINDOW_MINUTES = 60;

    /** Default number of messages that closes a window. */
    public static final int DEFAULT_MAX_MESSAGES = 50;

    /** Default overlap in messages, zero keeping the sequential cut of the first release. */
    public static final int DEFAULT_WINDOW_OVERLAP = 0;

    /**
     * Divisor of the overlap ceiling: the overlap must stay below {@code max_messages} divided by this.
     *
     * <p>Two, so a message can belong to two windows at most. A larger overlap would let the same turn
     * appear in three or more chunks, which multiplies the index and the near duplicate merging the
     * retrieval side then has to undo.
     */
    private static final int OVERLAP_BOUND_DIVISOR = 2;

    /** Span of one window in minutes, measured from its first message. */
    @JsonProperty("window_minutes")
    private int windowMinutes = DEFAULT_WINDOW_MINUTES;

    /** Number of messages that closes a window even when the time span is not reached. */
    @JsonProperty("max_messages")
    private int maxMessages = DEFAULT_MAX_MESSAGES;

    /** Number of trailing messages of a window that the next window repeats. */
    @JsonProperty("window_overlap")
    private int windowOverlap = DEFAULT_WINDOW_OVERLAP;

    /**
     * Parameters with the deployment defaults.
     *
     * @return default parameters
     */
    public static ChatAggregationParams defaults() {
        return new ChatAggregationParams();
    }

    /**
     * Window span in minutes, falling back to the default when the stored value is not usable.
     *
     * @return positive window span
     */
    public int effectiveWindowMinutes() {
        return windowMinutes > 0 ? windowMinutes : DEFAULT_WINDOW_MINUTES;
    }

    /**
     * Message ceiling of a window, falling back to the default when the stored value is not usable.
     *
     * @return positive message ceiling
     */
    public int effectiveMaxMessages() {
        return maxMessages > 0 ? maxMessages : DEFAULT_MAX_MESSAGES;
    }

    /**
     * Overlap in messages, clamped to the sequential cut when the stored value is not usable.
     *
     * <p>The clamp is not a second validation gate: the write path rejects an out of bound value, and a
     * knowledge base configured before this field existed simply carries none, which is the default.
     *
     * @return overlap between zero inclusive and {@link #maxWindowOverlap()} inclusive
     */
    public int effectiveWindowOverlap() {
        return windowOverlap > 0 ? Math.min(windowOverlap, maxWindowOverlap()) : DEFAULT_WINDOW_OVERLAP;
    }

    /**
     * Largest overlap the message ceiling admits.
     *
     * @return highest accepted overlap, which is one below half the message ceiling when it divides evenly
     */
    public int maxWindowOverlap() {
        return (effectiveMaxMessages() - 1) / OVERLAP_BOUND_DIVISOR;
    }

    /**
     * Tells whether the configured overlap is inside its bound.
     *
     * <p>The rule lives next to the two fields it relates rather than in the request payload, so the
     * single validation gate on the write path cannot drift from what the aggregator then does.
     *
     * @return {@code true} when the overlap is zero or more and stays below half the message ceiling
     */
    public boolean windowOverlapWithinBound() {
        return windowOverlap >= 0 && windowOverlap * OVERLAP_BOUND_DIVISOR < effectiveMaxMessages();
    }
}
