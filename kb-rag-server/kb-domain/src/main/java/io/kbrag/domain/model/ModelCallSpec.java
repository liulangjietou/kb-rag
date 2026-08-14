package io.kbrag.domain.model;

/**
 * Immutable facts known before one model request leaves the process.
 *
 * @param provider       provider identifier
 * @param capability     bounded capability name
 * @param model          model identifier
 * @param reservedTokens conservative upper bound reserved before the call
 *
 * @author owlzhangfq@gmail.com
 */
public record ModelCallSpec(String provider, String capability, String model, long reservedTokens) {

    /** Chat completion. */
    public static final String CHAT = "CHAT";
    /** Text embedding. */
    public static final String EMBEDDING = "EMBEDDING";
    /** Text reranking. */
    public static final String RERANK = "RERANK";
    /** Vision description/OCR. */
    public static final String VISION = "VISION";
    /** Multimodal embedding. */
    public static final String MULTIMODAL_EMBEDDING = "MULTIMODAL_EMBEDDING";

    public ModelCallSpec {
        reservedTokens = Math.max(1L, reservedTokens);
    }
}
