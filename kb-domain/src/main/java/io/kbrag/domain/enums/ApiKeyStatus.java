package io.kbrag.domain.enums;

/**
 * Lifecycle state of an open API key.
 *
 * <p>Disabling is kept distinct from deleting: a disabled key still answers with a dedicated error
 * code so the caller learns its credential was revoked rather than mistyped, while a deleted one is
 * indistinguishable from a key that never existed.
 *
 * @author owlzhangfq@gmail.com
 */
public enum ApiKeyStatus {

    /** Usable credential. */
    ENABLED,

    /** Revoked by an operator; authentication fails with {@code API_KEY_DISABLED}. */
    DISABLED;

    /**
     * Resolves a status from its literal, case insensitively.
     *
     * @param value request literal
     * @return matching status
     * @throws IllegalArgumentException when the literal matches no status
     */
    public static ApiKeyStatus from(String value) {
        if (value != null) {
            for (ApiKeyStatus status : values()) {
                if (status.name().equalsIgnoreCase(value.trim())) {
                    return status;
                }
            }
        }
        throw new IllegalArgumentException("unknown api key status: " + value);
    }
}
