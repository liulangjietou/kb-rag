package io.kbrag.domain.enums;

/**
 * Ordering mode of the rerank stage, the M14 contract section 5.
 *
 * <p>The two modes answer the same question with a different trust. {@link #SEMANTIC} orders by the
 * cross encoder relevance alone, which is the score an absolute threshold can act on. {@link #HYBRID}
 * mixes that relevance with the normalised BM25 score of the same candidate set so a strong keyword
 * match is not buried by a semantically close but lexically distant passage - a linear blend that
 * reproduces the model side hybrid rerank without depending on a model that offers it.
 *
 * <p>The mode only decides the <em>ordering</em>. The threshold keeps acting on the pure semantic
 * score whatever the mode is, because the blended score is min-max normalised inside one candidate
 * set and therefore not comparable across queries.
 *
 * @author owlzhangfq@gmail.com
 */
public enum RerankMode {

    /** Cross encoder relevance orders the result, the default. */
    SEMANTIC("semantic"),

    /** Linear blend of the semantic relevance and the normalised BM25 score orders the result. */
    HYBRID("hybrid");

    private final String code;

    RerankMode(String code) {
        this.code = code;
    }

    /**
     * Literal used in the API and in the configuration.
     *
     * @return API side value
     */
    public String code() {
        return code;
    }

    /**
     * Resolves a mode from its literal.
     *
     * @param value configuration or request value, case insensitive
     * @return matching mode, {@link #SEMANTIC} when blank or unknown
     */
    public static RerankMode from(String value) {
        if (value == null || value.isBlank()) {
            return SEMANTIC;
        }
        for (RerankMode mode : values()) {
            if (mode.code.equalsIgnoreCase(value.trim()) || mode.name().equalsIgnoreCase(value.trim())) {
                return mode;
            }
        }
        return SEMANTIC;
    }
}
