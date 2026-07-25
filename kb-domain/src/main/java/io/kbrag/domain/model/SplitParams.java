package io.kbrag.domain.model;

import lombok.Getter;
import lombok.ToString;

/**
 * Parameters of the fixed length splitter.
 *
 * <p>M1 ships a single strategy with the contract defaults of 600 estimated tokens per chunk and
 * 100 tokens of overlap; the values stay configurable so the knowledge base level configuration of
 * M2 can feed them without touching the splitter.
 */
@Getter
@ToString
public final class SplitParams {

    /** Contract default chunk size in estimated tokens. */
    public static final int DEFAULT_MAX_TOKENS = 600;

    /** Contract default overlap in estimated tokens. */
    public static final int DEFAULT_OVERLAP_TOKENS = 100;

    /** Maximum estimated tokens per chunk. */
    private final int maxTokens;

    /** Estimated tokens replayed from the tail of the previous chunk. */
    private final int overlapTokens;

    private SplitParams(int maxTokens, int overlapTokens) {
        this.maxTokens = maxTokens;
        this.overlapTokens = overlapTokens;
    }

    /**
     * Builds the parameter set, rejecting combinations that cannot terminate.
     *
     * @param maxTokens     maximum estimated tokens per chunk, must be positive
     * @param overlapTokens overlap in estimated tokens, must be zero or positive and below
     *                      {@code maxTokens}
     * @return validated parameters
     */
    public static SplitParams of(int maxTokens, int overlapTokens) {
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
        if (overlapTokens < 0 || overlapTokens >= maxTokens) {
            throw new IllegalArgumentException("overlapTokens must be within [0, maxTokens)");
        }
        return new SplitParams(maxTokens, overlapTokens);
    }

    /**
     * Builds the contract default parameter set.
     *
     * @return 600 token chunks with 100 token overlap
     */
    public static SplitParams defaults() {
        return new SplitParams(DEFAULT_MAX_TOKENS, DEFAULT_OVERLAP_TOKENS);
    }
}
