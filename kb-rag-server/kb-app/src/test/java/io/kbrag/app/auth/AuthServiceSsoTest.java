package io.kbrag.app.auth;

import io.kbrag.app.support.MybatisLambdaCache;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.AdminUser;
import io.kbrag.domain.entity.LoginAudit;
import io.kbrag.domain.entity.Tenant;
import io.kbrag.domain.enums.DirectoryBindResult;
import io.kbrag.domain.enums.LoginMode;
import io.kbrag.domain.enums.LoginResult;
import io.kbrag.domain.enums.UserSource;
import io.kbrag.domain.enums.UserStatus;
import io.kbrag.domain.mapper.AdminUserMapper;
import io.kbrag.domain.mapper.LoginAuditMapper;
import io.kbrag.domain.mapper.TenantMapper;
import io.kbrag.domain.model.DirectoryBindOutcome;
import io.kbrag.domain.model.ExternalIdentity;
import io.kbrag.domain.port.DirectoryAuthenticator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the single sign on landing rules of the M16 contract section 3.3 inside the
 * authentication service: an account is bound to the entry point that created it, an identity
 * provider outage is audited under its own reason so the lockout counter can exclude it, and a
 * directory login synchronises groups before the token is issued so the opening session already
 * sees the derived roles.
 *
 * @author owlzhangfq@gmail.com
 */
class AuthServiceSsoTest {

    private static final String USERNAME = "alice";
    private static final String IP = "203.0.113.7";

    private AdminUserMapper adminUserMapper;
    private LoginAuditMapper loginAuditMapper;
    private TokenStore tokenStore;
    private DirectoryAuthenticator directoryAuthenticator;
    private DirectoryGroupSyncService groupSyncService;
    private UserService userService;
    private PrincipalResolver principalResolver;
    private AuthService service;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.register(AdminUser.class, LoginAudit.class, Tenant.class);
        adminUserMapper = mock(AdminUserMapper.class);
        loginAuditMapper = mock(LoginAuditMapper.class);
        tokenStore = mock(TokenStore.class);
        directoryAuthenticator = mock(DirectoryAuthenticator.class);
        groupSyncService = mock(DirectoryGroupSyncService.class);
        userService = mock(UserService.class);
        principalResolver = mock(PrincipalResolver.class);
        // The lock window is derived from the audit table; an empty one means nobody is locked.
        when(loginAuditMapper.selectCount(any())).thenReturn(0L);
        service = new AuthService(adminUserMapper, loginAuditMapper, mock(TenantMapper.class),
                tokenStore, new KbProperties(), mock(BCryptPasswordEncoder.class),
                directoryAuthenticator, groupSyncService, userService, principalResolver);
    }

    @Test
    void shouldRefuseAnAssertionHittingAnAccountOfAnotherEntryPoint() {
        // A verified SAML assertion naming a LOCAL account: letting it in would make every
        // configured provider a master key for every other kind of account.
        when(adminUserMapper.selectOne(any())).thenReturn(user(UserSource.LOCAL));

        assertThrows(BizException.class, () -> service.completeExternalLogin(
                UserSource.SAML, new ExternalIdentity(USERNAME, null, null), IP));

        assertEquals(LoginResult.WRONG_LOGIN_MODE, lastAudit().getReason());
        verify(tokenStore, never()).issue(anyString());
    }

    @Test
    void shouldRefuseAVerifiedAssertionNamingNobody() {
        assertThrows(BizException.class, () -> service.completeExternalLogin(
                UserSource.OIDC, new ExternalIdentity("   ", null, null), IP));

        // A provider misconfiguration, not a user attempt: no audit row keyed on an empty name.
        verify(loginAuditMapper, never()).insert(any(LoginAudit.class));
    }

    @Test
    void shouldProvisionAndIssueOnTheFirstAssertion() {
        when(adminUserMapper.selectOne(any())).thenReturn(null);
        when(userService.provisionExternalUser(USERNAME, UserSource.OIDC, "Alice", null))
                .thenReturn(user(UserSource.OIDC));
        when(tokenStore.issue(USERNAME)).thenReturn("tok_1");

        LoginTicket ticket = service.completeExternalLogin(
                UserSource.OIDC, new ExternalIdentity(USERNAME, "Alice", null), IP);

        assertNotNull(ticket);
        verify(tokenStore).issue(USERNAME);
        // The fresh account has no cached permissions, and the session must resolve them anew.
        verify(principalResolver).evict(USERNAME);
        assertEquals(LoginResult.SUCCESS, lastAudit().getReason());
    }

    @Test
    void shouldAuditADirectoryOutageUnderItsOwnReason() {
        when(directoryAuthenticator.available()).thenReturn(true);
        when(adminUserMapper.selectOne(any())).thenReturn(null);
        when(directoryAuthenticator.bind(USERNAME, "pw"))
                .thenReturn(DirectoryBindOutcome.failure(DirectoryBindResult.SERVICE_UNAVAILABLE));

        assertThrows(BizException.class,
                () -> service.login(USERNAME, "pw", LoginMode.SSO, IP));

        // The dedicated reason is what lets the lockout counter exclude the outage: one domain
        // controller incident must not lock out everyone who retried during it.
        assertEquals(LoginResult.DIRECTORY_UNAVAILABLE, lastAudit().getReason());
    }

    @Test
    void shouldRefuseTheDirectoryDoorToAnAccountBornElsewhere() {
        when(directoryAuthenticator.available()).thenReturn(true);
        when(adminUserMapper.selectOne(any())).thenReturn(user(UserSource.OIDC));

        assertThrows(BizException.class,
                () -> service.login(USERNAME, "pw", LoginMode.SSO, IP));

        // Refused before the bind, so the directory is never probed with the attempt.
        verify(directoryAuthenticator, never()).bind(anyString(), anyString());
        assertEquals(LoginResult.WRONG_LOGIN_MODE, lastAudit().getReason());
    }

    @Test
    void shouldSynchroniseGroupsBeforeIssuingTheToken() {
        AdminUser user = user(UserSource.LDAP);
        when(directoryAuthenticator.available()).thenReturn(true);
        when(adminUserMapper.selectOne(any())).thenReturn(user);
        when(directoryAuthenticator.bind(USERNAME, "pw"))
                .thenReturn(DirectoryBindOutcome.success(List.of("cn=kb-admins,dc=corp")));
        when(groupSyncService.enabled()).thenReturn(true);
        when(tokenStore.issue(USERNAME)).thenReturn("tok_1");

        service.login(USERNAME, "pw", LoginMode.SSO, IP);

        // Synchronised before the token exists, so the session being opened already sees the
        // roles the directory groups map to - not the ones of the previous visit.
        InOrder order = inOrder(groupSyncService, tokenStore);
        order.verify(groupSyncService).sync(user, List.of("cn=kb-admins,dc=corp"));
        order.verify(tokenStore).issue(USERNAME);
    }

    private AdminUser user(UserSource source) {
        AdminUser user = new AdminUser();
        user.setUserId("usr_1");
        user.setUsername(USERNAME);
        user.setSource(source);
        user.setStatus(UserStatus.ENABLED);
        user.setMustChangePassword(0);
        return user;
    }

    private LoginAudit lastAudit() {
        ArgumentCaptor<LoginAudit> audit = ArgumentCaptor.forClass(LoginAudit.class);
        verify(loginAuditMapper).insert(audit.capture());
        return audit.getValue();
    }
}
