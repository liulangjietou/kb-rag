package io.kbrag.domain.enums;

/**
 * Lifecycle state of a tenant.
 *
 * <p>There is no deleted state on purpose: a tenant owns indices, files and audit rows, so the delete
 * semantics are "disable first, settle by hand". Disabling refuses every login of the tenant and lets
 * the request time principal check cut existing sessions on their next call.
 *
 * @author owlzhangfq@gmail.com
 */
public enum TenantStatus {

    /** Accounts of the tenant may log in. */
    ENABLED,

    /** Suspended by the platform administrator: every account of the tenant is refused. */
    DISABLED;

    /**
     * Resolves a status from its literal, case insensitively.
     *
     * @param value request literal
     * @return matching status
     * @throws IllegalArgumentException when the literal matches no status
     */
    public static TenantStatus from(String value) {
        if (value != null) {
            for (TenantStatus status : values()) {
                if (status.name().equalsIgnoreCase(value.trim())) {
                    return status;
                }
            }
        }
        throw new IllegalArgumentException("unknown tenant status: " + value);
    }
}
