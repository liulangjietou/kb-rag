package io.kbrag.domain.enums;

/**
 * Splitting strategy codes a knowledge base may be configured with.
 *
 * <p>The knowledge base column {@code index_config.split_strategy} stays a plain string so a new
 * strategy needs no schema change, but the set of codes an operator may write is closed: this enum is
 * what {@code KnowledgeBaseService} accepts, so a strategy that has no implementation behind it can
 * never be persisted and then fall through {@link io.kbrag.domain.service.SplitterRouter} into the
 * fixed length fallback while the console still displays it as configured.
 *
 * <p>Every constant here has to stay in step with a shipped implementation: the four that route
 * through the {@link io.kbrag.domain.service.TextSplitter} beans, plus {@code page}, which the index
 * pipeline dispatches to {@link io.kbrag.domain.service.PageSplitter} because it needs the page
 * boundaries as well as the text. {@code parent_child} is deliberately absent - it is a two level mode
 * switched on by {@code parent_child.enabled}, not a code an operator selects here.
 *
 * @author owlzhangfq@gmail.com
 */
public enum SplitStrategy {

    /** Fixed length splitting with overlap, the M1 default. */
    FIXED_LENGTH("fixed_length"),

    /** LLM judged semantic splitting, requirement section 4.3 strategy 7. */
    LLM_SEMANTIC("llm_semantic"),

    /** Literal or regular expression delimiter, the M14 contract section 4. */
    SEPARATOR("separator"),

    /** Markdown heading depth, the M14 contract section 4. */
    HEADING("heading"),

    /** Parser reported page boundaries, the M14 contract section 4. */
    PAGE("page");

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
     * @return matching strategy, {@code null} when no implementation answers to that code
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
