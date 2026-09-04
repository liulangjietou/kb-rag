package io.kbrag.app.auth;

import io.kbrag.app.support.MybatisLambdaCache;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.AdminUser;
import io.kbrag.domain.entity.Role;
import io.kbrag.domain.entity.Tenant;
import io.kbrag.domain.entity.UserRole;
import io.kbrag.domain.enums.TenantStatus;
import io.kbrag.domain.enums.UserSource;
import io.kbrag.domain.enums.UserStatus;
import io.kbrag.domain.mapper.AdminUserMapper;
import io.kbrag.domain.mapper.RoleMapper;
import io.kbrag.domain.mapper.TenantMapper;
import io.kbrag.domain.mapper.UserRoleMapper;
import io.kbrag.domain.service.BizIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    private EmailIdentityClaimService emailIdentityClaimService;
    private UserRoleMapper userRoleMapper;
    private RoleMapper roleMapper;
    private TenantMapper tenantMapper;
    private BizIdGenerator idGenerator;
    private ConsoleSessionService consoleSessionService;
    private PrincipalResolver principalResolver;
    private KbProperties properties;
    private UserService service;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.register(AdminUser.class, Role.class, Tenant.class, UserRole.class);
        adminUserMapper = mock(AdminUserMapper.class);
        when(adminUserMapper.insert(any(AdminUser.class))).thenReturn(1);
        emailIdentityClaimService = mock(EmailIdentityClaimService.class);
        when(emailIdentityClaimService.claimForNewUser(any(), any(), any()))
                .thenAnswer(invocation -> {
                    String email = invocation.getArgument(2);
                    return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
                });
        userRoleMapper = mock(UserRoleMapper.class);
        roleMapper = mock(RoleMapper.class);
        tenantMapper = mock(TenantMapper.class);
        idGenerator = mock(BizIdGenerator.class);
        when(idGenerator.userId()).thenReturn(USER_ID);
        consoleSessionService = mock(ConsoleSessionService.class);
        principalResolver = mock(PrincipalResolver.class);
        properties = new KbProperties();
        service = new UserService(adminUserMapper, emailIdentityClaimService,
                userRoleMapper, roleMapper,
                tenantMapper, idGenerator, mock(BCryptPasswordEncoder.class),
                consoleSessionService, principalResolver, properties);
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

    @Test
    void shouldCreateAnEnabledLocalAccountWithTheCompleteNormalizedEmail() {
        Tenant tenant = new Tenant();
        tenant.setTenantId("tnt_registered");
        tenant.setStatus(TenantStatus.ENABLED);
        Role role = new Role();
        role.setRoleId("role_registered");
        role.setTenantId("tnt_registered");
        when(tenantMapper.selectOne(any())).thenReturn(tenant);
        when(roleMapper.selectList(any())).thenReturn(List.of(role));
        Locale previous = Locale.getDefault();
        AdminUser user;
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            user = service.createRegisteredUser(" Person@EXAMPLE.COM ", " Alice ",
                    "$2a$10$tesHlq8Mb9Tj200DjJwf6Om6ibfxNmblzIJp2uQ0goV2Qmm0uCt4W",
                    List.of("role_registered"), "tnt_registered");
        } finally {
            Locale.setDefault(previous);
        }

        assertEquals("person@example.com", user.getUsername());
        assertEquals("person@example.com", user.getEmail());
        assertEquals("Alice", user.getDisplayName());
        assertEquals(UserSource.LOCAL, user.getSource());
        assertEquals(UserStatus.ENABLED, user.getStatus());
        assertEquals(0, user.getMustChangePassword());
        ArgumentCaptor<UserRole> binding = ArgumentCaptor.forClass(UserRole.class);
        verify(userRoleMapper).insert(binding.capture());
        assertEquals("role_registered", binding.getValue().getRoleId());
    }

    @Test
    void shouldNotReleaseOldEmailClaimWhenOptimisticUpdateLoses() {
        AdminUser existing = new AdminUser();
        existing.setUserId(USER_ID);
        existing.setUsername(USERNAME);
        existing.setEmail("old@example.com");
        when(adminUserMapper.selectOne(any())).thenReturn(existing);
        when(emailIdentityClaimService.claimReplacement(USER_ID, "new@example.com"))
                .thenReturn("new@example.com");
        when(adminUserMapper.updateById(existing)).thenReturn(0);

        assertThrows(BizException.class,
                () -> service.update(USER_ID, "Alice", "new@example.com"));

        verify(emailIdentityClaimService, never()).releaseReplacedContact(
                any(), any(), any(), any());
    }

    @Test
    void shouldNotRevokeSessionsWhenStatusUpdateLosesOptimisticLock() {
        AdminUser existing = existingUser();
        when(adminUserMapper.selectOne(any())).thenReturn(existing);
        when(adminUserMapper.updateById(existing)).thenReturn(0);

        assertThrows(BizException.class,
                () -> service.updateStatus(USER_ID, UserStatus.DISABLED));

        verifyNoInteractions(consoleSessionService, principalResolver);
    }

    @Test
    void shouldNotRevokeSessionsWhenPasswordResetLosesOptimisticLock() {
        AdminUser existing = existingUser();
        BCryptPasswordEncoder encoder = mock(BCryptPasswordEncoder.class);
        when(encoder.encode("Secure123")).thenReturn("encoded");
        service = new UserService(adminUserMapper, emailIdentityClaimService,
                userRoleMapper, roleMapper, tenantMapper, idGenerator, encoder,
                consoleSessionService, principalResolver, properties);
        when(adminUserMapper.selectOne(any())).thenReturn(existing);
        when(adminUserMapper.updateById(existing)).thenReturn(0);

        assertThrows(BizException.class,
                () -> service.resetPassword(USER_ID, "Secure123"));

        verifyNoInteractions(consoleSessionService, principalResolver);
    }

    @Test
    void shouldNotDeleteRolesOrRevokeSessionsWhenTenantMoveLosesOptimisticLock() {
        AdminUser existing = existingUser();
        existing.setTenantId("tnt_old");
        Tenant target = new Tenant();
        target.setTenantId("tnt_new");
        target.setStatus(TenantStatus.ENABLED);
        when(adminUserMapper.selectOne(any())).thenReturn(existing);
        when(tenantMapper.selectOne(any())).thenReturn(target);
        when(adminUserMapper.updateById(existing)).thenReturn(0);

        assertThrows(BizException.class,
                () -> service.moveTenant(USER_ID, "tnt_new"));

        verify(userRoleMapper, never()).deleteByUserId(any());
        verifyNoInteractions(consoleSessionService, principalResolver);
    }

    @Test
    void shouldFailBeforeBindingRolesWhenTheUserRowCannotBePersisted() {
        when(adminUserMapper.insert(any(AdminUser.class))).thenReturn(0);

        assertThrows(BizException.class,
                () -> service.provisionDirectoryUser(USERNAME));

        verifyNoInteractions(userRoleMapper);
    }

    private AdminUser existingUser() {
        AdminUser user = new AdminUser();
        user.setUserId(USER_ID);
        user.setUsername(USERNAME);
        user.setStatus(UserStatus.ENABLED);
        user.setSource(UserSource.LOCAL);
        return user;
    }
}
