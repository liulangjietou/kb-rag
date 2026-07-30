package io.kbrag.app.auth;

import io.kbrag.app.support.MybatisLambdaCache;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.constant.BuiltinTenants;
import io.kbrag.domain.constant.PermissionCodes;
import io.kbrag.domain.entity.KnowledgeBase;
import io.kbrag.domain.entity.Permission;
import io.kbrag.domain.entity.Role;
import io.kbrag.domain.entity.RoleKbScope;
import io.kbrag.domain.entity.RolePermission;
import io.kbrag.domain.entity.UserRole;
import io.kbrag.domain.mapper.DocAclMapper;
import io.kbrag.domain.mapper.KnowledgeBaseMapper;
import io.kbrag.domain.mapper.PermissionMapper;
import io.kbrag.domain.mapper.RoleKbScopeMapper;
import io.kbrag.domain.mapper.RoleMapper;
import io.kbrag.domain.mapper.RolePermissionMapper;
import io.kbrag.domain.mapper.UserRoleMapper;
import io.kbrag.domain.service.BizIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the platform level rule of the permission granting, the M16 tenant isolation invariant:
 * {@code tenant:manage} may only be held by a role of the default tenant.
 *
 * <p>该权限码既能建租户停租户，又让查询绕过用户表与角色表的租户行过滤（见 {@code KbTenantLineHandler}），
 * 落到子租户的角色上等于该租户的管理员接管整个平台。权限码目录是全租户共用的，所以子租户的管理员在
 * 角色编辑页确实能看到并勾上它 —— 这道门就是拦这个动作的。
 *
 * @author owlzhangfq@gmail.com
 */
class RoleServicePlatformPermissionTest {

    private static final String TENANT_ROLE_ID = "role_tenant1";
    private static final String DEFAULT_ROLE_ID = "role_superadmin000";
    private static final String OTHER_TENANT_ID = "tnt_acme0000000001";

    private PermissionMapper permissionMapper;
    private RolePermissionMapper rolePermissionMapper;
    private RoleService service;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.register(Role.class, Permission.class, RolePermission.class,
                RoleKbScope.class, UserRole.class, KnowledgeBase.class);
        permissionMapper = mock(PermissionMapper.class);
        rolePermissionMapper = mock(RolePermissionMapper.class);
        service = new RoleService(mock(RoleMapper.class), permissionMapper, rolePermissionMapper,
                mock(RoleKbScopeMapper.class), mock(UserRoleMapper.class), mock(DocAclMapper.class),
                mock(KnowledgeBaseMapper.class), mock(BizIdGenerator.class),
                mock(PrincipalResolver.class));
    }

    @Test
    void shouldRefusePlatformOnlyPermissionOnATenantRole() {
        assertThrows(BizException.class, () -> service.replacePermissions(
                role(TENANT_ROLE_ID, OTHER_TENANT_ID),
                List.of(PermissionCodes.KB_READ, PermissionCodes.TENANT_MANAGE)));

        // Refused before the grant rows are written, so a partially granted set never reaches the table.
        verify(rolePermissionMapper, never()).insert(any(RolePermission.class));
    }

    @Test
    void shouldAllowPlatformOnlyPermissionOnADefaultTenantRole() {
        // 平台运营方就住在默认租户里，租户管理页是它的正当入口。
        when(permissionMapper.selectList(any())).thenReturn(List.of(
                permission(PermissionCodes.TENANT_MANAGE), permission(PermissionCodes.KB_READ)));

        service.replacePermissions(role(DEFAULT_ROLE_ID, BuiltinTenants.DEFAULT_TENANT_ID),
                List.of(PermissionCodes.TENANT_MANAGE, PermissionCodes.KB_READ));

        ArgumentCaptor<RolePermission> granted = ArgumentCaptor.forClass(RolePermission.class);
        verify(rolePermissionMapper, times(2)).insert(granted.capture());
        assertEquals(DEFAULT_ROLE_ID, granted.getAllValues().get(0).getRoleId());
    }

    @Test
    void shouldGrantOrdinaryPermissionsToATenantRole() {
        when(permissionMapper.selectList(any())).thenReturn(List.of(permission(PermissionCodes.KB_READ)));

        service.replacePermissions(role(TENANT_ROLE_ID, OTHER_TENANT_ID),
                List.of(PermissionCodes.KB_READ));

        verify(rolePermissionMapper).insert(any(RolePermission.class));
    }

    private Role role(String roleId, String tenantId) {
        Role role = new Role();
        role.setRoleId(roleId);
        role.setTenantId(tenantId);
        role.setCode("SUPER_ADMIN");
        return role;
    }

    private Permission permission(String code) {
        Permission permission = new Permission();
        permission.setCode(code);
        return permission;
    }
}
