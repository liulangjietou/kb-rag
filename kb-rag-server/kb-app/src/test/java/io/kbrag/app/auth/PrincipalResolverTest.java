package io.kbrag.app.auth;

import io.kbrag.app.support.MybatisLambdaCache;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.entity.AdminUser;
import io.kbrag.domain.entity.Role;
import io.kbrag.domain.entity.RoleKbScope;
import io.kbrag.domain.entity.RolePermission;
import io.kbrag.domain.entity.Tenant;
import io.kbrag.domain.entity.UserRole;
import io.kbrag.domain.enums.TenantStatus;
import io.kbrag.domain.enums.UserSource;
import io.kbrag.domain.enums.UserStatus;
import io.kbrag.domain.mapper.AdminUserMapper;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the tenant gate of the principal resolver, the M16 contract section 3.1: a session of a
 * disabled tenant is refused on its very next resolution, the answer is cached until an eviction,
 * and the blunt {@code evictAll} of the tenant switch is what turns "disabled in the console" into
 * "refused on the next request".
 *
 * @author owlzhangfq@gmail.com
 */
class PrincipalResolverTest {

    private static final String USERNAME = "alice";
    private static final String TENANT_ID = "tnt_acme0000000001";

    private AdminUserMapper adminUserMapper;
    private TenantMapper tenantMapper;
    private PrincipalResolver resolver;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.register(AdminUser.class, Tenant.class, UserRole.class,
                Role.class, RolePermission.class, RoleKbScope.class);
        adminUserMapper = mock(AdminUserMapper.class);
        tenantMapper = mock(TenantMapper.class);
        UserRoleMapper userRoleMapper = mock(UserRoleMapper.class);
        when(userRoleMapper.selectList(any())).thenReturn(List.of());
        resolver = new PrincipalResolver(adminUserMapper, userRoleMapper, mock(RoleMapper.class),
                mock(RolePermissionMapper.class), mock(RoleKbScopeMapper.class), tenantMapper,
                new FakePrincipalCache());
    }

    @Test
    void shouldRefuseTheSessionOfADisabledTenant() {
        when(adminUserMapper.selectOne(any())).thenReturn(enabledUser());
        when(tenantMapper.selectOne(any())).thenReturn(tenant(TenantStatus.DISABLED));

        // Disabling a tenant must cut every session of the tenant on its next request, not when
        // the individual tokens expire.
        BizException refusal = assertThrows(BizException.class, () -> resolver.resolve(USERNAME));
        assertTrue(refusal.getMessage().contains("tenant"));
    }

    @Test
    void shouldResolveAndCacheTheCallerOfAnEnabledTenant() {
        when(adminUserMapper.selectOne(any())).thenReturn(enabledUser());
        when(tenantMapper.selectOne(any())).thenReturn(tenant(TenantStatus.ENABLED));

        UserPrincipal principal = resolver.resolve(USERNAME);
        resolver.resolve(USERNAME);

        assertEquals(TENANT_ID, principal.tenantId());
        // The second resolution is served from the cache, which is why the tenant switch has to
        // evict explicitly instead of waiting for anything.
        verify(adminUserMapper, times(1)).selectOne(any());
    }

    @Test
    void shouldSeeTheDisabledTenantOnlyAfterTheEviction() {
        when(adminUserMapper.selectOne(any())).thenReturn(enabledUser());
        when(tenantMapper.selectOne(any())).thenReturn(tenant(TenantStatus.ENABLED));
        resolver.resolve(USERNAME);

        when(tenantMapper.selectOne(any())).thenReturn(tenant(TenantStatus.DISABLED));
        // Still cached: the disable has not propagated yet.
        resolver.resolve(USERNAME);

        resolver.evictAll();

        assertThrows(BizException.class, () -> resolver.resolve(USERNAME));
    }

    private AdminUser enabledUser() {
        AdminUser user = new AdminUser();
        user.setUserId("usr_1");
        user.setTenantId(TENANT_ID);
        user.setUsername(USERNAME);
        user.setSource(UserSource.LOCAL);
        user.setStatus(UserStatus.ENABLED);
        return user;
    }

    private Tenant tenant(TenantStatus status) {
        Tenant tenant = new Tenant();
        tenant.setTenantId(TENANT_ID);
        tenant.setCode("ACME");
        tenant.setStatus(status);
        return tenant;
    }

    /**
     * 进程内的缓存替身。
     *
     * <p>这些用例验证的是"命中就不再查库""失效之后才看得到变化"，因此需要一个真会记住东西的缓存，
     * mock 表达不了。不直接用 {@code LocalPrincipalCache}：那是端口实现，按本仓惯例住在
     * kb-infrastructure，而 kb-app 不依赖那个模块——为了测试方便去破坏模块方向，代价比这十行大。
     * 它自身的契约由 kb-infrastructure 的 {@code LocalPrincipalCacheTest} 覆盖。
     */
    private static final class FakePrincipalCache implements PrincipalCache {

        private final Map<String, UserPrincipal> entries = new ConcurrentHashMap<>();

        @Override
        public UserPrincipal get(String username) {
            return entries.get(username);
        }

        @Override
        public void put(String username, UserPrincipal principal) {
            entries.put(username, principal);
        }

        @Override
        public void evict(String username) {
            entries.remove(username);
        }

        @Override
        public void evictAll() {
            entries.clear();
        }
    }
}
