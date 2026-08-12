package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.domain.entity.Role;

import java.util.List;

/**
 * Role row, with its grants and its data scope.
 *
 * @param roleId          role business id
 * @param tenantId        owning tenant business id (M16); the built in role codes repeat once per
 *                        tenant, so a platform operator reading this list across tenants needs the
 *                        owner to tell two rows named alike apart
 * @param code            stable role code, not editable
 * @param name            display label
 * @param description     purpose note
 * @param builtin         {@code true} for a role shipped with the product, which cannot be deleted
 * @param kbScopeAll      {@code true} when the role sees every knowledge base
 * @param kbIds           scoped knowledge bases, empty when {@code kbScopeAll}
 * @param permissionCodes granted permission codes
 *
 * @author owlzhangfq@gmail.com
 */
public record RoleResponse(
        @JsonProperty("role_id") String roleId,
        @JsonProperty("tenant_id") String tenantId,
        String code,
        String name,
        String description,
        boolean builtin,
        @JsonProperty("kb_scope_all") boolean kbScopeAll,
        @JsonProperty("kb_ids") List<String> kbIds,
        @JsonProperty("permission_codes") List<String> permissionCodes) {

    /**
     * Maps one role onto the transport shape.
     *
     * @param role            role record
     * @param permissionCodes granted permission codes, may be {@code null}
     * @param kbIds           scoped knowledge base ids, may be {@code null}
     * @return role row
     */
    public static RoleResponse from(Role role, List<String> permissionCodes, List<String> kbIds) {
        return new RoleResponse(
                role.getRoleId(),
                role.getTenantId(),
                role.getCode(),
                role.getName(),
                role.getDescription(),
                role.builtin(),
                role.kbScopeAll(),
                kbIds == null ? List.of() : kbIds,
                permissionCodes == null ? List.of() : permissionCodes);
    }
}
