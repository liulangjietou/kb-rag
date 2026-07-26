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
 * <p>The windows are cut without overlap: a conversation is already a sequence of short turns, so a
 * sliding window would duplicate almost every message across two chunks and inflate the index for no
 * recall gain. A window closes on whichever bound is reached first, elapsed time or message count.
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

    /** Span of one window in minutes, measured from its first message. */
    @JsonProperty("window_minutes")
    private int windowMinutes = DEFAULT_WINDOW_MINUTES;

    /** Number of messages that closes a window even when the time span is not reached. */
    @JsonProperty("max_messages")
    private int maxMessages = DEFAULT_MAX_MESSAGES;

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
}
