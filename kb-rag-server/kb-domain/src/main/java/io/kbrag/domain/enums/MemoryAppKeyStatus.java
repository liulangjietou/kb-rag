package io.kbrag.domain.enums;

/**
 * Lifecycle state of a memory key.
 *
 * <p>Same shape as {@link ApiKeyStatus} and kept separate on purpose: the two credentials guard
 * different surfaces, and sharing the enum would weld their lifecycles together the first time one
 * of them needs an extra state.
 *
 * @author owlzhangfq@gmail.com
 */
public enum MemoryAppKeyStatus {

    /** Usable credential. */
    ENABLED,

    /** Revoked by an operator; authentication fails with a dedicated error code. */
    DISABLED;

    /**
     * Resolves a status from its literal, case insensitively.
     *
     * @param value request literal
     * @return matching status
     * @throws IllegalArgumentException when the literal matches no status
     */
    public static MemoryAppKeyStatus from(String value) {
        if (value != null) {
            for (MemoryAppKeyStatus status : values()) {
                if (status.name().equalsIgnoreCase(value.trim())) {
                    return status;
                }
            }
        }
        throw new IllegalArgumentException("unknown memory app key status: " + value);
    }
}
