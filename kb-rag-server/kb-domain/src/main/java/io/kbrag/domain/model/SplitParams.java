package io.kbrag.domain.model;

import lombok.Getter;
import lombok.ToString;

/**
 * Parameters of the fixed length splitter.
 *
 * <p>M1 ships a single strategy with the contract defaults of 600 estimated tokens per chunk and
 * 100 tokens of overlap; the values stay configurable so the knowledge base level configuration of
 * M2 can feed them without touching the splitter.
 *
 * @author owlzhangfq@gmail.com
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

    /**
     * Object storage cache coordinates, only read by {@link io.kbrag.domain.service.LlmSemanticTextSplitter}
     * so a fresh window is not re-priced on every rebuild of the same document; {@code null} for every
     * other strategy and for a caller that does not need caching, for example a unit test.
     */
    private final CacheContext cacheContext;

    /**
     * Block delimiter of the M14 {@code separator} strategy, {@code null} for every other strategy; a
     * {@code null} lets the separator splitter fall back to its own default rather than forcing every
     * caller of the two argument factory to name one.
     */
    private final String separator;

    /** Whether {@link #separator} is a regular expression, only read by the {@code separator} strategy. */
    private final boolean separatorIsRegex;

    /**
     * Markdown heading depth of the M14 {@code heading} strategy, {@code 0} for every other strategy; a
     * {@code 0} lets the heading splitter fall back to its own default depth.
     */
    private final int headingLevel;

    private SplitParams(int maxTokens, int overlapTokens, CacheContext cacheContext,
                        String separator, boolean separatorIsRegex, int headingLevel) {
        this.maxTokens = maxTokens;
        this.overlapTokens = overlapTokens;
        this.cacheContext = cacheContext;
        this.separator = separator;
        this.separatorIsRegex = separatorIsRegex;
        this.headingLevel = headingLevel;
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
        return of(maxTokens, overlapTokens, null);
    }

    /**
     * Builds the parameter set carrying the object storage cache coordinates of the LLM semantic
     * splitter.
     *
     * @param maxTokens     maximum estimated tokens per chunk, must be positive
     * @param overlapTokens overlap in estimated tokens, must be zero or positive and below
     *                      {@code maxTokens}
     * @param cacheContext  cache coordinates, {@code null} disables caching for this call
     * @return validated parameters
     */
    public static SplitParams of(int maxTokens, int overlapTokens, CacheContext cacheContext) {
        return of(maxTokens, overlapTokens, cacheContext, null, false, 0);
    }

    /**
     * Builds the parameter set carrying the M14 {@code separator} and {@code heading} strategy inputs
     * alongside the LLM cache coordinates.
     *
     * @param maxTokens        maximum estimated tokens per chunk, must be positive
     * @param overlapTokens    overlap in estimated tokens, must be zero or positive and below
     *                         {@code maxTokens}
     * @param cacheContext     cache coordinates, {@code null} disables caching for this call
     * @param separator        {@code separator} strategy delimiter, {@code null} keeps the strategy default
     * @param separatorIsRegex whether {@code separator} is a regular expression
     * @param headingLevel     {@code heading} strategy depth, {@code 0} keeps the strategy default
     * @return validated parameters
     */
    public static SplitParams of(int maxTokens, int overlapTokens, CacheContext cacheContext,
                                 String separator, boolean separatorIsRegex, int headingLevel) {
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
        if (overlapTokens < 0 || overlapTokens >= maxTokens) {
            throw new IllegalArgumentException("overlapTokens must be within [0, maxTokens)");
        }
        return new SplitParams(maxTokens, overlapTokens, cacheContext, separator, separatorIsRegex,
                headingLevel);
    }

    /**
     * Builds the contract default parameter set.
     *
     * @return 600 token chunks with 100 token overlap
     */
    public static SplitParams defaults() {
        return new SplitParams(DEFAULT_MAX_TOKENS, DEFAULT_OVERLAP_TOKENS, null, null, false, 0);
    }

    /**
     * Object storage coordinates of one document version's split cache.
     *
     * @param kbId       owning knowledge base business id
     * @param docId      owning document business id
     * @param versionId  document version business id
     * @param contentHash SHA-256 of the version's original byte stream, the identity half of the cache key
     */
    public record CacheContext(String kbId, String docId, String versionId, String contentHash) {
    }
}
