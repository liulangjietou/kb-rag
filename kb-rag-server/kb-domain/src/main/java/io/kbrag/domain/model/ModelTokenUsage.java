package io.kbrag.domain.model;

/**
 * Token counts returned by a provider response.
 *
 * @param inputTokens  prompt/input tokens
 * @param outputTokens generated tokens
 * @param totalTokens  provider total, or input plus output when the response omits it
 * @param known        whether the provider returned usage data
 *
 * @author owlzhangfq@gmail.com
 */
public record ModelTokenUsage(long inputTokens, long outputTokens, long totalTokens, boolean known) {

    /** Provider returned no usable token counters. */
    public static ModelTokenUsage unknown() {
        return new ModelTokenUsage(0L, 0L, 0L, false);
    }

    /**
     * Normalizes non-negative counters and derives total when only the two sides are present.
     */
    public ModelTokenUsage {
        inputTokens = Math.max(0L, inputTokens);
        outputTokens = Math.max(0L, outputTokens);
        long sides = inputTokens > Long.MAX_VALUE - outputTokens
                ? Long.MAX_VALUE : inputTokens + outputTokens;
        totalTokens = Math.max(0L, Math.max(totalTokens, sides));
    }
}
