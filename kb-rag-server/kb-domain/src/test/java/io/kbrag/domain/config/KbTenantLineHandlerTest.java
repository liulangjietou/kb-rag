package io.kbrag.domain.config;

import io.kbrag.domain.constant.BuiltinTenants;
import io.kbrag.domain.constant.PermissionCodes;
import io.kbrag.domain.context.UserContextHolder;
import io.kbrag.domain.enums.UserSource;
import io.kbrag.domain.model.UserPrincipal;
import net.sf.jsqlparser.expression.StringValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the tenant fence of the M16 contract section 1.3: only the root aggregate tables are
 * fenced, a request without a console principal is skipped entirely, and the platform operator's
 * cross tenant window opens on users and roles and nothing else.
 *
 * @author owlzhangfq@gmail.com
 */
class KbTenantLineHandlerTest {

    private static final String TENANT_ID = "tnt_acme0000000001";
    private static final String USERS_TABLE = "t_kb_admin_user";
    private static final String ROLES_TABLE = "t_kb_role";
    private static final String BASES_TABLE = "t_kb_knowledge_base";

    private final KbTenantLineHandler handler = new KbTenantLineHandler();

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void shouldSkipEveryTableWhenThereIsNoConsolePrincipal() {
        // The open API, the background tasks and the startup runners locate rows by exact business
        // id; the tenant a clause would need is simply not on those threads.
        assertTrue(handler.ignoreTable(BASES_TABLE));
        assertTrue(handler.ignoreTable(USERS_TABLE));
    }

    @Test
    void shouldSkipTablesThatAreNotRootAggregates() {
        UserContextHolder.set(principal(TENANT_ID, Set.of()));

        // A subordinate table reaches its tenant through its root; fencing it too would demand a
        // tenant_id column on every table for no additional isolation.
        assertTrue(handler.ignoreTable("t_kb_document"));
        assertTrue(handler.ignoreTable("t_kb_chunk"));
    }

    @Test
    void shouldFenceTheRootAggregatesOfATenantCaller() {
        UserContextHolder.set(principal(TENANT_ID, Set.of()));

        assertFalse(handler.ignoreTable(BASES_TABLE));
        assertFalse(handler.ignoreTable(USERS_TABLE));
        assertFalse(handler.ignoreTable(ROLES_TABLE));
        assertEquals(TENANT_ID, ((StringValue) handler.getTenantId()).getValue());
    }

    @Test
    void shouldOpenTheOperatorWindowOnUsersAndRolesOnly() {
        UserContextHolder.set(principal(TENANT_ID, Set.of(PermissionCodes.TENANT_MANAGE)));

        // The window exists so the operator can create the first account of a fresh tenant; the
        // daily work tables stay fenced even for the operator.
        assertTrue(handler.ignoreTable(USERS_TABLE));
        assertTrue(handler.ignoreTable(ROLES_TABLE));
        assertFalse(handler.ignoreTable(BASES_TABLE));
    }

    @Test
    void shouldFallBackToTheDefaultTenantForAPrincipalWithoutOne() {
        UserContextHolder.set(principal(null, Set.of()));

        // A principal minted without a tenant by an older cache row is skipped by ignoreTable, but
        // the value must still be sane in case some path asks for it anyway.
        assertTrue(handler.ignoreTable(BASES_TABLE));
        assertEquals(BuiltinTenants.DEFAULT_TENANT_ID,
                ((StringValue) handler.getTenantId()).getValue());
    }

    private UserPrincipal principal(String tenantId, Set<String> permissions) {
        return new UserPrincipal("usr_1", tenantId, "alice", "Alice", UserSource.LOCAL,
                Set.of(), Set.of(), permissions, false, Set.of());
    }
}
