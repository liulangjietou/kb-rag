package io.kbrag.app.auth;

import io.kbrag.app.support.MybatisLambdaCache;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.AdminUser;
import io.kbrag.domain.entity.Role;
import io.kbrag.domain.entity.UserRole;
import io.kbrag.domain.enums.UserSource;
import io.kbrag.domain.mapper.AdminUserMapper;
import io.kbrag.domain.mapper.RoleMapper;
import io.kbrag.domain.mapper.TenantMapper;
import io.kbrag.domain.mapper.UserRoleMapper;
import io.kbrag.domain.service.BizIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers the first-login provisioning of the M16 contract section 6: with group synchronisation
 * enabled a directory account gets no default role at all, because a default pushed into the
 * manually granted set could never be revoked by the synchronisation - the directory groups are
 * then the only source of truth for what the account holds.
 *
 * <p>The other identity sources keep the configured default, and a misconfigured default role name
 * degrades to a roleless account instead of failing the login the provider already accepted.
 *
 * @author owlzhangfq@gmail.com
 */
class UserServiceProvisionTest {

    private static final String USER_ID = "usr_new";
    private static final String USERNAME = "alice";
    private static final String VIEWER_ROLE_ID = "role_viewer";

    private AdminUserMapper adminUserMapper;
    private UserRoleMapper userRoleMapper;
    private RoleMapper roleMapper;
    private BizIdGenerator idGenerator;
    private KbProperties properties;
    private UserService service;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.register(AdminUser.class, Role.class);
        adminUserMapper = mock(AdminUserMapper.class);
        userRoleMapper = mock(UserRoleMapper.class);
        roleMapper = mock(RoleMapper.class);
        idGenerator = mock(BizIdGenerator.class);
        when(idGenerator.userId()).thenReturn(USER_ID);
        properties = new KbProperties();
        service = new UserService(adminUserMapper, userRoleMapper, roleMapper,
                mock(TenantMapper.class), idGenerator, mock(BCryptPasswordEncoder.class),
                mock(TokenStore.class), mock(PrincipalResolver.class), properties);
    }

    @Test
    void shouldNotGrantTheDefaultRoleWhenGroupSyncOwnsTheRoleSet() {
        properties.getAuth().getLdap().getGroupSync().setEnabled(true);

        AdminUser user = service.provisionDirectoryUser(USERNAME);

        // A default role granted here would sit in the manually granted set forever: the
        // synchronisation only replaces LDAP_SYNC grants and could never revoke it.
        assertEquals(USER_ID, user.getUserId());
        verify(adminUserMapper).insert(any(AdminUser.class));
        verifyNoInteractions(userRoleMapper);
        verifyNoInteractions(roleMapper);
    }

    @Test
    void shouldGrantTheConfiguredDefaultRoleWithoutGroupSync() {
        Role viewer = new Role();
        viewer.setRoleId(VIEWER_ROLE_ID);
        viewer.setCode(properties.getAuth().getLdap().getDefaultRoleCode());
        when(roleMapper.selectOne(any())).thenReturn(viewer);

        service.provisionDirectoryUser(USERNAME);

        ArgumentCaptor<UserRole> bound = ArgumentCaptor.forClass(UserRole.class);
        verify(userRoleMapper).insert(bound.capture());
        assertEquals(VIEWER_ROLE_ID, bound.getValue().getRoleId());
    }

    @Test
    void shouldKeepTheDefaultRoleForNonDirectorySourcesEvenWithGroupSyncEnabled() {
        // Group synchronisation is a directory concept; an OIDC account has no groups to be
        // synchronised from and keeps the configured read only default.
        properties.getAuth().getLdap().getGroupSync().setEnabled(true);
        Role viewer = new Role();
        viewer.setRoleId(VIEWER_ROLE_ID);
        when(roleMapper.selectOne(any())).thenReturn(viewer);

        service.provisionExternalUser(USERNAME, UserSource.OIDC, "Alice", null);

        verify(userRoleMapper).insert(any(UserRole.class));
    }

    @Test
    void shouldProvisionARolelessAccountWhenTheDefaultRoleIsMissing() {
        when(roleMapper.selectOne(any())).thenReturn(null);

        AdminUser user = service.provisionDirectoryUser(USERNAME);

        // A misconfigured role name must not fail the login the directory already accepted: the
        // account lands on an empty console an operator can fix by granting a role.
        assertEquals(USERNAME, user.getUsername());
        assertNull(user.getPasswordHash());
        verify(userRoleMapper, never()).insert(any(UserRole.class));
    }
}
