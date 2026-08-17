package io.kbrag.app.auth;

import io.kbrag.app.support.MybatisLambdaCache;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.constant.PermissionCodes;
import io.kbrag.domain.entity.Role;
import io.kbrag.domain.entity.RolePermission;
import io.kbrag.domain.entity.Tenant;
import io.kbrag.domain.enums.TenantStatus;
import io.kbrag.domain.mapper.RoleMapper;
import io.kbrag.domain.mapper.RolePermissionMapper;
import io.kbrag.domain.mapper.TenantMapper;
import io.kbrag.domain.service.BizIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the tenant administration of the M16 contract section 3.1: creating a tenant seeds it with
 * copies of the built in roles bindings included, the code is normalized and unique, the built in
 * default tenant cannot be disabled, and disabling any other tenant evicts every cached principal so
 * its sessions die on the next request.
 *
 * @author owlzhangfq@gmail.com
 */
class TenantServiceTest {

    private static final String NEW_TENANT_ID = "tnt_acme0000000001";
    private static final int BUILTIN_ROLE_COUNT = 5;

    private TenantMapper tenantMapper;
    private RoleMapper roleMapper;
    private RolePermissionMapper rolePermissionMapper;
    private RoleService roleService;
    private PrincipalResolver principalResolver;
    private TenantService service;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.register(Tenant.class, Role.class, RolePermission.class);
        tenantMapper = mock(TenantMapper.class);
        roleMapper = mock(RoleMapper.class);
        rolePermissionMapper = mock(RolePermissionMapper.class);
        roleService = mock(RoleService.class);
        principalResolver = mock(PrincipalResolver.class);
        BizIdGenerator idGenerator = mock(BizIdGenerator.class);
        when(idGenerator.tenantId()).thenReturn(NEW_TENANT_ID);
        when(idGenerator.roleId()).thenReturn("role_c1", "role_c2", "role_c3", "role_c4", "role_c5");
        service = new TenantService(tenantMapper, roleMapper, rolePermissionMapper, roleService,
                idGenerator, principalResolver);
    }

    @Test
    void shouldCreateATenantAndCopyTheBuiltinRolesWithTheirBindings() {
        when(tenantMapper.selectOne(any())).thenReturn(null);
        when(roleMapper.selectList(any())).thenReturn(builtinTemplates());
        when(rolePermissionMapper.selectList(any())).thenReturn(List.of(binding("role_t1", "kb:read")));

        Tenant created = service.create(" acme ", "Acme Corp");

        // The code is the immutable operational handle, normalized once at the door.
        assertEquals("ACME", created.getCode());
        assertEquals(TenantStatus.ENABLED, created.getStatus());
        assertEquals(NEW_TENANT_ID, created.getTenantId());

        // A tenant whose role list starts empty could not even grant its first administrator, so
        // every built in role of the default tenant is copied with a fresh id under the new tenant.
        ArgumentCaptor<Role> copiedRoles = ArgumentCaptor.forClass(Role.class);
        verify(roleMapper, times(BUILTIN_ROLE_COUNT)).insert(copiedRoles.capture());
        Set<String> copiedCodes = copiedRoles.getAllValues().stream()
                .map(Role::getCode)
                .collect(Collectors.toSet());
        assertEquals(BUILTIN_ROLE_COUNT, copiedCodes.size());
        assertTrue(copiedRoles.getAllValues().stream()
                .allMatch(role -> NEW_TENANT_ID.equals(role.getTenantId())));

        // Bindings ride along, granted to the copied role through the single granting gate rather
        // than inserted here - which is what makes the platform level rule apply to the copy too.
        ArgumentCaptor<Role> grantedRoles = ArgumentCaptor.forClass(Role.class);
        ArgumentCaptor<List<String>> grantedCodes = ArgumentCaptor.forClass(List.class);
        verify(roleService, times(BUILTIN_ROLE_COUNT))
                .replacePermissions(grantedRoles.capture(), grantedCodes.capture());
        assertTrue(grantedCodes.getAllValues().stream()
                .allMatch(codes -> codes.equals(List.of("kb:read"))));
        assertTrue(grantedRoles.getAllValues().stream()
                .noneMatch(role -> "role_t1".equals(role.getRoleId())));
    }

    @Test
    void shouldNotCopyPlatformOnlyPermissionsIntoANewTenant() {
        // 默认租户的 SUPER_ADMIN 持有 tenant:manage。逐行照抄会把它搬进每一个新租户，于是该租户的
        // 管理员能建租户、停租户，并越过用户表与角色表的租户行过滤 —— 隔离在建租户这一步就失效了。
        when(tenantMapper.selectOne(any())).thenReturn(null);
        when(roleMapper.selectList(any())).thenReturn(List.of(template("role_t1", "SUPER_ADMIN", 1)));
        when(rolePermissionMapper.selectList(any())).thenReturn(List.of(
                binding("role_t1", PermissionCodes.TENANT_MANAGE),
                binding("role_t1", "kb:read")));

        service.create("acme", "Acme Corp");

        ArgumentCaptor<List<String>> grantedCodes = ArgumentCaptor.forClass(List.class);
        verify(roleService).replacePermissions(any(Role.class), grantedCodes.capture());
        assertEquals(List.of("kb:read"), grantedCodes.getValue());
    }

    @Test
    void shouldRefuseADuplicateTenantCode() {
        when(tenantMapper.selectOne(any())).thenReturn(tenant("ACME", 0, TenantStatus.ENABLED));

        assertThrows(BizException.class, () -> service.create("acme", "Acme again"));

        verify(tenantMapper, never()).insert(any(Tenant.class));
    }

    @Test
    void shouldRefuseDisablingTheBuiltinDefaultTenant() {
        when(tenantMapper.selectOne(any())).thenReturn(tenant("DEFAULT", 1, TenantStatus.ENABLED));

        // Disabling the tenant the platform operator lives in locks everyone out with no recovery
        // path inside the product.
        assertThrows(BizException.class,
                () -> service.updateStatus("tnt_default0000000", TenantStatus.DISABLED));

        verify(tenantMapper, never()).updateById(any(Tenant.class));
        verify(principalResolver, never()).evictAll();
    }

    @Test
    void shouldEvictEveryCachedPrincipalWhenATenantIsDisabled() {
        when(tenantMapper.selectOne(any())).thenReturn(tenant("ACME", 0, TenantStatus.ENABLED));

        service.updateStatus(NEW_TENANT_ID, TenantStatus.DISABLED);

        ArgumentCaptor<Tenant> updated = ArgumentCaptor.forClass(Tenant.class);
        verify(tenantMapper).updateById(updated.capture());
        assertEquals(TenantStatus.DISABLED, updated.getValue().getStatus());
        // Live sessions hold cached principals; the eviction is what makes their next request hit
        // the resolver again and be refused there.
        verify(principalResolver).evictAll();
    }

    @Test
    void shouldRenameTheDisplayNameAndKeepTheCodeImmutable() {
        when(tenantMapper.selectOne(any())).thenReturn(tenant("ACME", 0, TenantStatus.ENABLED));

        service.rename(NEW_TENANT_ID, "Acme Holdings");

        ArgumentCaptor<Tenant> updated = ArgumentCaptor.forClass(Tenant.class);
        verify(tenantMapper).updateById(updated.capture());
        assertEquals("Acme Holdings", updated.getValue().getName());
        // The index naming tenant segment and the runbooks refer to the code; it never moves.
        assertEquals("ACME", updated.getValue().getCode());
    }

    @Test
    void shouldRejectAnUnknownTenant() {
        when(tenantMapper.selectOne(any())).thenReturn(null);

        assertThrows(BizException.class, () -> service.get("tnt_missing"));
    }

    @Test
    void shouldUpdateMonthlyModelQuotaWithoutCancellingTheTenant() {
        when(tenantMapper.selectOne(any())).thenReturn(tenant("ACME", 0, TenantStatus.ENABLED));

        service.updateModelQuota(NEW_TENANT_ID, 5_000_000L);

        ArgumentCaptor<Tenant> updated = ArgumentCaptor.forClass(Tenant.class);
        verify(tenantMapper).updateById(updated.capture());
        assertEquals(5_000_000L, updated.getValue().getMonthlyTokenQuota());
        assertEquals(TenantStatus.ENABLED, updated.getValue().getStatus());
    }

    private List<Role> builtinTemplates() {
        return List.of(
                template("role_t1", "SUPER_ADMIN", 1),
                template("role_t2", "KB_ADMIN", 0),
                template("role_t3", "KB_EDITOR", 0),
                template("role_t4", "KB_VIEWER", 0),
                template("role_t5", "AUDITOR", 0));
    }

    private Role template(String roleId, String code, int kbScopeAll) {
        Role role = new Role();
        role.setRoleId(roleId);
        role.setCode(code);
        role.setName(code);
        role.setBuiltin(1);
        role.setKbScopeAll(kbScopeAll);
        return role;
    }

    private RolePermission binding(String roleId, String permissionCode) {
        RolePermission grant = new RolePermission();
        grant.setRoleId(roleId);
        grant.setPermissionCode(permissionCode);
        return grant;
    }

    private Tenant tenant(String code, int builtin, TenantStatus status) {
        Tenant tenant = new Tenant();
        tenant.setTenantId(NEW_TENANT_ID);
        tenant.setCode(code);
        tenant.setName(code);
        tenant.setBuiltin(builtin);
        tenant.setStatus(status);
        return tenant;
    }
}
