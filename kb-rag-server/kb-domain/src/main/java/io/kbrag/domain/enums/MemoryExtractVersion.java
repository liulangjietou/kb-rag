package io.kbrag.domain.enums;

/**
 * Extraction pipeline variant of a memory rule, the M19 contract.
 *
 * <p>PRO reads the entity's existing memories back before extracting so the model can merge, update
 * and deduplicate against them; LITE extracts from the new conversation alone. The trade is quality
 * against tokens and latency, which is why it is a per rule choice rather than a system setting.
 *
 * @author owlzhangfq@gmail.com
 */
public enum MemoryExtractVersion {

    /** Extraction with old memory merge and deduplication. */
    PRO,

    /** Single pass extraction from the new conversation only. */
    LITE;

    /**
     * Resolves a version from its literal, case insensitively.
     *
     * @param value request literal
     * @return matching version
     * @throws IllegalArgumentException when the literal matches no version
     */
    public static MemoryExtractVersion from(String value) {
        if (value != null) {
            for (MemoryExtractVersion version : values()) {
                if (version.name().equalsIgnoreCase(value.trim())) {
                    return version;
                }
            }
        }
        throw new IllegalArgumentException("unknown memory extract version: " + value);
    }
}
