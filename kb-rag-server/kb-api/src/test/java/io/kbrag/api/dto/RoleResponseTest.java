package io.kbrag.api.dto;

import io.kbrag.domain.entity.Role;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers what the role row has to carry for the console list to be readable across tenants.
 *
 * <p>{@code t_kb_role} is one of the two tables a caller holding {@code tenant:manage} queries
 * unfenced, and the built in roles repeat once per tenant under the same code and the same name. Drop
 * the owning tenant from this row and the operator's list becomes a set of duplicate looking entries
 * with no way to tell which one an edit would hit.
 *
 * @author owlzhangfq@gmail.com
 */
class RoleResponseTest {

    @Test
    void shouldCarryTheOwningTenantSoTheOperatorCanTellRepeatedBuiltinRolesApart() {
        RoleResponse first = RoleResponse.from(role("role_1", "tenant_default", "KB_ADMIN"),
                List.of("kb:read"), List.of());
        RoleResponse second = RoleResponse.from(role("role_2", "tenant_acme", "KB_ADMIN"),
                List.of("kb:read"), List.of());

        assertEquals(first.code(), second.code());
        assertEquals("tenant_default", first.tenantId());
        assertEquals("tenant_acme", second.tenantId());
    }

    @Test
    void shouldTurnMissingGrantsIntoEmptyListsRatherThanNulls() {
        RoleResponse response = RoleResponse.from(role("role_1", "tenant_default", "VIEWER"), null, null);

        assertTrue(response.permissionCodes().isEmpty());
        assertTrue(response.kbIds().isEmpty());
    }

    private static Role role(String roleId, String tenantId, String code) {
        Role role = new Role();
        role.setRoleId(roleId);
        role.setTenantId(tenantId);
        role.setCode(code);
        role.setName("知识库管理员");
        role.setBuiltin(1);
        role.setKbScopeAll(1);
        return role;
    }
}
