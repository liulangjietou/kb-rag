package io.kbrag.app.auth;

import io.kbrag.app.support.MybatisLambdaCache;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.entity.AdminUser;
import io.kbrag.domain.entity.Role;
import io.kbrag.domain.entity.RoleAppScope;
import io.kbrag.domain.entity.RoleKbScope;
import io.kbrag.domain.entity.RolePermission;
import io.kbrag.domain.entity.Tenant;
import io.kbrag.domain.entity.UserRole;
import io.kbrag.domain.enums.UserSource;
import io.kbrag.domain.enums.UserStatus;
import io.kbrag.domain.mapper.AdminUserMapper;
import io.kbrag.domain.mapper.RoleAppScopeMapper;
import io.kbrag.domain.mapper.RoleKbScopeMapper;
import io.kbrag.domain.mapper.RoleMapper;
import io.kbrag.domain.mapper.RolePermissionMapper;
import io.kbrag.domain.mapper.TenantMapper;
import io.kbrag.domain.mapper.UserRoleMapper;
import io.kbrag.domain.model.UserPrincipal;
import io.kbrag.domain.port.PrincipalCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 新旧缓存兼容、范围并集与功能权限独立性的边界。 */
class PrincipalAppScopeTest {
    private final AdminUserMapper users = mock(AdminUserMapper.class);
    private final RoleMapper roles = mock(RoleMapper.class);
    private final RoleAppScopeMapper scopes = mock(RoleAppScopeMapper.class);
    private final PrincipalCache cache = mock(PrincipalCache.class);
    private PrincipalResolver resolver;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.register(AdminUser.class, Role.class, RoleAppScope.class, RoleKbScope.class,
                RolePermission.class, Tenant.class, UserRole.class);
        var bindings = mock(UserRoleMapper.class);
        var one = new UserRole();
        one.setRoleId("r1");
        var two = new UserRole();
        two.setRoleId("r2");
        when(bindings.selectList(any())).thenReturn(List.of(one, two));
        var user = new AdminUser();
        user.setUserId("u1");
        user.setUsername("alice");
        user.setTenantId("t1");
        user.setSource(UserSource.LOCAL);
        user.setStatus(UserStatus.ENABLED);
        when(users.selectOne(any())).thenReturn(user);
        resolver = new PrincipalResolver(users, bindings, roles, mock(RolePermissionMapper.class),
                mock(RoleKbScopeMapper.class), scopes, mock(TenantMapper.class), cache);
    }

    @Test
    void shouldReloadLegacyJsonCacheAndUnionApplicationScopesWithoutAddingFunctionPermissions() {
        UserPrincipal old = JsonUtil.parse("""
                {"userId":"u1","tenantId":"t1","username":"alice","source":"LOCAL",
                 "roleCodes":[],"roleIds":[],"permissions":["app:read"],"kbScopeAll":true,"kbIds":[]}
                """, UserPrincipal.class);
        assertNull(old.appIds());
        assertFalse(old.canAccessApp("app_1"));
        when(cache.get("alice")).thenReturn(old);
        when(roles.selectList(any())).thenReturn(List.of(role("r1", false), role("r2", false)));
        when(scopes.selectList(any())).thenReturn(List.of(scope("r1", "app_1"), scope("r2", "app_2"), scope("r2", "app_1")));

        var principal = resolver.resolve("alice");
        assertEquals(Set.of("app_1", "app_2"), principal.appIds());
        assertTrue(principal.canAccessApp("app_1"));
        assertFalse(principal.canAccessApp("app_3"));
        assertFalse(principal.hasPermission("app:use"));
        verify(cache).put("alice", principal);
        assertEquals(principal, JsonUtil.parse(JsonUtil.toJson(principal), UserPrincipal.class));
    }

    @Test
    void shouldUseAnyAllScopeRoleAndAvoidLoadingIrrelevantBindings() {
        when(roles.selectList(any())).thenReturn(List.of(role("r1", false), role("r2", true)));
        var principal = resolver.resolve("alice");
        assertTrue(principal.appScopeAll());
        assertTrue(principal.canAccessApp("app_3"));
        assertFalse(principal.canAccessApp(null));
        verifyNoInteractions(scopes);
    }

    @Test
    void shouldReuseCompleteCacheAndKeepLegacyJavaCallersUnprivileged() {
        var principal = new UserPrincipal("u1", "t1", "alice", "Alice", UserSource.LOCAL,
                Set.of(), Set.of(), Set.of("app:read", "search:debug"), true, Set.of());
        when(cache.get("alice")).thenReturn(principal);
        assertSame(principal, resolver.resolve("alice"));
        assertFalse(principal.canAccessApp("app_1"));
        verifyNoInteractions(users, roles, scopes);
    }

    private Role role(String id, boolean all) {
        Role role = new Role();
        role.setRoleId(id);
        role.setCode(id);
        role.setTenantId("t1");
        role.setAppScopeAll(all);
        return role;
    }

    @Test
    void shouldResolveFreshScopesWithoutReadingOrReplacingTheSharedCache() {
        when(roles.selectList(any())).thenReturn(List.of(role("r1", true)));
        assertTrue(resolver.resolveFresh("alice").appScopeAll());
        when(roles.selectList(any())).thenReturn(List.of(role("r1", false)));
        var revoked = resolver.resolveFresh("alice");
        assertFalse(revoked.appScopeAll());
        assertTrue(revoked.appIds().isEmpty());
        verifyNoInteractions(cache);
    }

    private RoleAppScope scope(String roleId, String appId) {
        var scope = new RoleAppScope();
        scope.setRoleId(roleId);
        scope.setAppId(appId);
        return scope;
    }
}
