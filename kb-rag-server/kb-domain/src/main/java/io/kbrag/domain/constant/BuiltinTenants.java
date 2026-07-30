package io.kbrag.domain.constant;

/**
 * The tenant the V17 migration seeds.
 *
 * <p>The identifier is a literal rather than something generated at first start, because every
 * pre-existing root aggregate row is assigned to it through a column DEFAULT: a random identifier
 * would turn "which tenant owns the legacy rows" into an UPDATE that has to run exactly right once.
 *
 * <p>The default tenant is also the compatibility anchor of the index naming scheme: its indices keep
 * the pre-M16 names without a tenant segment, so an upgraded deployment keeps every alias it has.
 *
 * @author owlzhangfq@gmail.com
 */
public final class BuiltinTenants {

    /** Business id of the built in default tenant, every upgraded row lands here. */
    public static final String DEFAULT_TENANT_ID = "tnt_default0000000";

    /** Stable code of the built in default tenant. */
    public static final String DEFAULT_CODE = "DEFAULT";

    private BuiltinTenants() {
    }

    /**
     * Tells whether one tenant id names the built in default tenant.
     *
     * @param tenantId tenant business id, may be {@code null}
     * @return {@code true} for the default tenant or a missing id (legacy callers predate tenancy)
     */
    public static boolean isDefault(String tenantId) {
        return tenantId == null || tenantId.isBlank() || DEFAULT_TENANT_ID.equals(tenantId);
    }
}
