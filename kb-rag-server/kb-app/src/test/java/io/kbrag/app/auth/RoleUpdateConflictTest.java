package io.kbrag.app.auth;

import io.kbrag.app.support.MybatisLambdaCache;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.entity.Role;
import io.kbrag.domain.mapper.DocAclMapper;
import io.kbrag.domain.mapper.KnowledgeBaseMapper;
import io.kbrag.domain.mapper.PermissionMapper;
import io.kbrag.domain.mapper.RoleKbScopeMapper;
import io.kbrag.domain.mapper.RoleMapper;
import io.kbrag.domain.mapper.RolePermissionMapper;
import io.kbrag.domain.mapper.UserRoleMapper;
import io.kbrag.domain.service.BizIdGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 角色主行更新失败时，不应继续替换权限或数据范围。 */
class RoleUpdateConflictTest {

    @Test
    void shouldRefuseGrantChangesWhenTheRoleUpdateConflicts() {
        MybatisLambdaCache.register(Role.class);
        RoleMapper roleMapper = mock(RoleMapper.class);
        RolePermissionMapper permissionMapper = mock(RolePermissionMapper.class);
        RoleKbScopeMapper kbScopeMapper = mock(RoleKbScopeMapper.class);
        RoleAppScopeService appScopes = mock(RoleAppScopeService.class);
        Role role = new Role();
        role.setId(1L);
        role.setRoleId("role_employee");
        role.setTenantId("tenant_one");
        role.setLockVersion(2);
        when(roleMapper.selectOne(any())).thenReturn(role);
        when(roleMapper.updateById(any(Role.class))).thenReturn(0);
        RoleService service = new RoleService(roleMapper, mock(PermissionMapper.class), permissionMapper,
                kbScopeMapper, mock(UserRoleMapper.class), mock(DocAclMapper.class),
                mock(KnowledgeBaseMapper.class), mock(BizIdGenerator.class),
                mock(PrincipalResolver.class), appScopes);

        assertThrows(BizException.class, () -> service.update("role_employee", "员工", "",
                true, List.of(), List.of(), false, List.of()));

        verify(permissionMapper, never()).deleteByRoleId(any());
        verify(kbScopeMapper, never()).deleteByRoleId(any());
        verify(appScopes, never()).replace(any(), any());
    }
}
