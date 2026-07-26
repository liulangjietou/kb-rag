package io.kbrag.domain.enums;

/**
 * Splitting strategy codes the {@link io.kbrag.domain.service.SplitterRouter} resolves against the
 * registered {@link io.kbrag.domain.service.TextSplitter} beans.
 *
 * <p>The knowledge base column {@code index_config.split_strategy} stays a plain string so a strategy
 * nobody has modelled here yet (page, heading, regex, table row - requirement section 4.3) can still be
 * stored without a schema or enum change; this enum only names the strategies that ship with a real
 * {@link io.kbrag.domain.service.TextSplitter} implementation today, which the router and the
 * configuration fast-fail both need a fixed literal for.
 *
 * @author owlzhangfq@gmail.com
 */
public enum SplitStrategy {

    /** Fixed length splitting with overlap, the M1 default. */
    FIXED_LENGTH("fixed_length"),

    /** LLM judged semantic splitting, requirement section 4.3 strategy 7. */
    LLM_SEMANTIC("llm_semantic");

    private final String code;

    SplitStrategy(String code) {
        this.code = code;
    }

    /**
     * Literal persisted in {@code index_config.split_strategy} and in the split fingerprint.
     *
     * @return strategy code
     */
    public String code() {
        return code;
    }

    /**
     * Resolves a strategy from its persisted code.
     *
     * @param value stored code, case insensitive
     * @return matching strategy, {@code null} when the code names a strategy modelled elsewhere
     */
    public static SplitStrategy from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (SplitStrategy strategy : values()) {
            if (strategy.code.equalsIgnoreCase(value.trim())) {
                return strategy;
            }
        }
        return null;
    }
}
