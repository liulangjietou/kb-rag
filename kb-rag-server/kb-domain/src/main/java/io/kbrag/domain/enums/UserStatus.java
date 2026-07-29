package io.kbrag.domain.enums;

/**
 * Lifecycle state of a console account.
 *
 * <p>Disabling is kept distinct from soft deleting: a disabled account keeps its role bindings and its
 * audit history, so an operator can suspend someone who left the team for a quarter and hand the
 * account back intact. Deleting is what removes the bindings.
 *
 * @author owlzhangfq@gmail.com
 */
public enum UserStatus {

    /** Account may log in. */
    ENABLED,

    /** Suspended by an operator: login is refused and existing sessions are revoked. */
    DISABLED;

    /**
     * Resolves a status from its literal, case insensitively.
     *
     * @param value request literal
     * @return matching status
     * @throws IllegalArgumentException when the literal matches no status
     */
    public static UserStatus from(String value) {
        if (value != null) {
            for (UserStatus status : values()) {
                if (status.name().equalsIgnoreCase(value.trim())) {
                    return status;
                }
            }
        }
        throw new IllegalArgumentException("unknown user status: " + value);
    }
}
