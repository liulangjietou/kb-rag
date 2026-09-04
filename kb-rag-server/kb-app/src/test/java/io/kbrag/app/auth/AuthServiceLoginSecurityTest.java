package io.kbrag.app.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.app.support.MybatisLambdaCache;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.AdminUser;
import io.kbrag.domain.entity.LoginAudit;
import io.kbrag.domain.entity.Tenant;
import io.kbrag.domain.enums.DirectoryBindResult;
import io.kbrag.domain.enums.LoginMode;
import io.kbrag.domain.enums.LoginResult;
import io.kbrag.domain.enums.TenantStatus;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceLoginSecurityTest {

    private static final String GENERIC_FAILURE = "invalid username or password";
    private static final String LOCKED_FAILURE = "account temporarily locked, retry after 15 minutes";
    private static final String IP = "203.0.113.7";
    private static final int ATTEMPT_COUNT = 16;

    private AdminUserMapper adminUserMapper;
    private LoginAuditMapper loginAuditMapper;
    private TenantMapper tenantMapper;
    private BCryptPasswordEncoder passwordEncoder;
    private DirectoryAuthenticator directoryAuthenticator;
    private ConsoleSessionService consoleSessionService;
    private final List<LoginAudit> audits = new CopyOnWriteArrayList<>();
    private final AtomicInteger countedFailures = new AtomicInteger();
    private AuthService service;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.register(AdminUser.class, LoginAudit.class, Tenant.class);
        adminUserMapper = mock(AdminUserMapper.class);
        loginAuditMapper = mock(LoginAuditMapper.class);
        tenantMapper = mock(TenantMapper.class);
        passwordEncoder = mock(BCryptPasswordEncoder.class);
        directoryAuthenticator = mock(DirectoryAuthenticator.class);

        when(loginAuditMapper.selectOne(any())).thenReturn(null);
        when(loginAuditMapper.selectCount(any())).thenAnswer(ignored -> (long) countedFailures.get());
        when(adminUserMapper.updateById(any(AdminUser.class))).thenReturn(1);
        when(loginAuditMapper.insert(any(LoginAudit.class))).thenAnswer(invocation -> {
            LoginAudit audit = invocation.getArgument(0);
            audits.add(audit);
            if (Integer.valueOf(0).equals(audit.getSuccess())
                    && audit.getReason() != LoginResult.DIRECTORY_UNAVAILABLE) {
                countedFailures.incrementAndGet();
            }
            return 1;
        });

        consoleSessionService = mock(ConsoleSessionService.class);
        LoginSuccessService loginSuccessService = new LoginSuccessService(
                adminUserMapper, loginAuditMapper, consoleSessionService, mock(UserService.class),
                mock(DirectoryGroupSyncService.class), mock(PrincipalResolver.class));
        service = new AuthService(adminUserMapper, loginAuditMapper, tenantMapper,
                consoleSessionService, new KbProperties(), passwordEncoder, directoryAuthenticator,
                new LoginFailureAuditService(loginAuditMapper), loginSuccessService,
                new LoginAttemptGuard());
    }

    @Test
    void shouldHideAccountStateWhileKeepingPreciseAuditReasons() {
        // 本测试逐项验证返回文案；锁定计数由独立并发测试覆盖。
        when(loginAuditMapper.selectCount(any())).thenReturn(0L);

        when(adminUserMapper.selectOne(any())).thenReturn(null);
        assertGenericFailure(() -> service.login("unknown", "pw", LoginMode.LOCAL, IP),
                LoginResult.USER_NOT_FOUND);

        when(adminUserMapper.selectOne(any())).thenReturn(user("alice", UserSource.LOCAL, UserStatus.ENABLED));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);
        assertGenericFailure(() -> service.login("alice", "bad", LoginMode.LOCAL, IP),
                LoginResult.BAD_PASSWORD);

        when(adminUserMapper.selectOne(any())).thenReturn(user("alice", UserSource.LDAP, UserStatus.ENABLED));
        assertGenericFailure(() -> service.login("alice", "pw", LoginMode.LOCAL, IP),
                LoginResult.WRONG_LOGIN_MODE);

        when(directoryAuthenticator.available()).thenReturn(true);
        when(adminUserMapper.selectOne(any())).thenReturn(user("alice", UserSource.LOCAL, UserStatus.ENABLED));
        assertGenericFailure(() -> service.login("alice", "pw", LoginMode.SSO, IP),
                LoginResult.WRONG_LOGIN_MODE);

        when(adminUserMapper.selectOne(any())).thenReturn(user("alice", UserSource.LOCAL, UserStatus.DISABLED));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
        assertGenericFailure(() -> service.login("alice", "pw", LoginMode.LOCAL, IP),
                LoginResult.ACCOUNT_DISABLED);

        AdminUser enabled = user("alice", UserSource.LOCAL, UserStatus.ENABLED);
        when(adminUserMapper.selectOne(any())).thenReturn(enabled);
        Tenant disabledTenant = new Tenant();
        disabledTenant.setStatus(TenantStatus.DISABLED);
        when(tenantMapper.selectOne(any())).thenReturn(disabledTenant);
        assertGenericFailure(() -> service.login("alice", "pw", LoginMode.LOCAL, IP),
                LoginResult.TENANT_DISABLED);

        when(adminUserMapper.selectOne(any())).thenReturn(user("alice", UserSource.OIDC, UserStatus.ENABLED));
        assertGenericFailure(() -> service.completeExternalLogin(UserSource.SAML,
                        new ExternalIdentity("alice", null, null), IP),
                LoginResult.WRONG_LOGIN_MODE);
    }

    @Test
    void shouldPayTheBcryptCostForUnknownAndDirectoryAccountsOnTheLocalDoor() {
        when(adminUserMapper.selectOne(any()))
                .thenReturn(null)
                .thenReturn(user("alice", UserSource.LDAP, UserStatus.ENABLED));

        assertThrows(BizException.class,
                () -> service.login("unknown", "pw", LoginMode.LOCAL, IP));
        assertThrows(BizException.class,
                () -> service.login("alice", "pw", LoginMode.LOCAL, IP));

        verify(passwordEncoder, times(2)).matches("pw", AuthService.DUMMY_PASSWORD_HASH);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldPreferAnExactEmailAndKeepLegacyUsernameCompatibility() {
        when(passwordEncoder.matches("pw", "hash")).thenReturn(true);
        when(adminUserMapper.selectOne(any())).thenAnswer(invocation -> {
            LambdaQueryWrapper<AdminUser> wrapper = invocation.getArgument(0);
            wrapper.getSqlSegment();
            if (wrapper.getParamNameValuePairs().containsValue("person@example.com")) {
                return user("person@example.com", UserSource.LOCAL, UserStatus.ENABLED);
            }
            if (wrapper.getParamNameValuePairs().containsValue("person")) {
                return user("person", UserSource.LOCAL, UserStatus.ENABLED);
            }
            if (wrapper.getParamNameValuePairs().containsValue("admin")) {
                return user("admin", UserSource.LOCAL, UserStatus.ENABLED);
            }
            return null;
        });

        Locale previousLocale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            service.login(" Person@Example.com ", "pw", LoginMode.LOCAL, IP);
            service.login(" ADMIN ", "pw", LoginMode.LOCAL, IP);
        } finally {
            Locale.setDefault(previousLocale);
        }

        assertEquals("person@example.com", audits.get(0).getUsername());
        assertEquals("admin", audits.get(1).getUsername());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldFoldDifferentSuffixesToOneLegacyLoginAndGuardKey() {
        LoginAttemptGuard guard = spy(new LoginAttemptGuard(16, "registration-login-test"));
        service = new AuthService(adminUserMapper, loginAuditMapper, tenantMapper,
                mock(ConsoleSessionService.class), new KbProperties(), passwordEncoder, directoryAuthenticator,
                new LoginFailureAuditService(loginAuditMapper), mock(LoginSuccessService.class), guard);
        when(passwordEncoder.matches("pw", "hash")).thenReturn(false);
        when(adminUserMapper.selectOne(any())).thenAnswer(invocation -> {
            LambdaQueryWrapper<AdminUser> wrapper = invocation.getArgument(0);
            wrapper.getSqlSegment();
            return wrapper.getParamNameValuePairs().containsValue("admin")
                    ? user("admin", UserSource.LOCAL, UserStatus.ENABLED) : null;
        });

        assertThrows(BizException.class,
                () -> service.login("admin@corp-one.example", "pw", LoginMode.LOCAL, IP));
        assertThrows(BizException.class,
                () -> service.login("admin@corp-two.example", "pw", LoginMode.LOCAL, IP));

        verify(guard, times(2)).acquire("admin", IP);
        assertTrue(audits.stream().allMatch(audit -> "admin".equals(audit.getUsername())));
    }

    @Test
    void shouldReloadAQueuedLocalAccountAfterPasswordRotationBeforeIssuingAToken()
            throws Exception {
        String email = "person@example.com";
        LoginAttemptGuard guard = new LoginAttemptGuard(16, "queued-password-rotation-test");
        ConsoleSessionService queuedSessionService = mock(ConsoleSessionService.class);
        LoginSuccessService successService = mock(LoginSuccessService.class);
        service = new AuthService(adminUserMapper, loginAuditMapper, tenantMapper,
                queuedSessionService, new KbProperties(), passwordEncoder, directoryAuthenticator,
                new LoginFailureAuditService(loginAuditMapper), successService, guard);
        AdminUser beforeRotation = user(email, UserSource.LOCAL, UserStatus.ENABLED);
        beforeRotation.setPasswordHash("old-hash");
        AdminUser afterRotation = user(email, UserSource.LOCAL, UserStatus.ENABLED);
        afterRotation.setPasswordHash("new-hash");
        AtomicReference<AdminUser> persisted = new AtomicReference<>(beforeRotation);
        CountDownLatch canonicalLookupFinished = new CountDownLatch(1);
        when(adminUserMapper.selectOne(any())).thenAnswer(ignored -> {
            canonicalLookupFinished.countDown();
            return persisted.get();
        });
        when(passwordEncoder.matches("old-password", "old-hash")).thenReturn(true);
        when(passwordEncoder.matches("old-password", "new-hash")).thenReturn(false);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<String> queued;
        try {
            try (LoginAttemptGuard.Permit ignored = guard.acquire(email, IP)) {
                queued = executor.submit(() -> {
                    try {
                        service.login(email, "old-password", LoginMode.LOCAL, IP);
                        return "unexpected success";
                    } catch (BizException exception) {
                        return exception.getMessage();
                    }
                });
                assertTrue(canonicalLookupFinished.await(2, TimeUnit.SECONDS));
                persisted.set(afterRotation);
                queuedSessionService.revokeAll(email);
            }

            assertEquals(GENERIC_FAILURE, queued.get(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        verify(passwordEncoder).matches("old-password", "new-hash");
        verify(passwordEncoder, never()).matches("old-password", "old-hash");
        verify(queuedSessionService).revokeAll(email);
        verify(successService, never()).issueExisting(any(), anyString(), anyString());
    }

    @Test
    void shouldUseTheCurrentAccountStatusInsideTheLoginGuard() {
        AdminUser enabledBeforeGuard = user("alice", UserSource.LOCAL, UserStatus.ENABLED);
        AdminUser disabledInsideGuard = user("alice", UserSource.LOCAL, UserStatus.DISABLED);
        when(adminUserMapper.selectOne(any())).thenReturn(enabledBeforeGuard, disabledInsideGuard);
        when(passwordEncoder.matches("pw", "hash")).thenReturn(true);

        assertGenericFailure(() -> service.login("alice", "pw", LoginMode.LOCAL, IP),
                LoginResult.ACCOUNT_DISABLED);
    }

    @Test
    void shouldFailPasswordChangeWhenTheAccountWasUpdatedConcurrently() {
        AdminUser user = user("alice", UserSource.LOCAL, UserStatus.ENABLED);
        user.setPasswordHash("old-hash");
        when(adminUserMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches("old-password", "old-hash")).thenReturn(true);
        when(passwordEncoder.encode("new-password")).thenReturn("new-hash");
        when(adminUserMapper.updateById(user)).thenReturn(0);

        BizException exception = assertThrows(BizException.class,
                () -> service.changePassword("alice", "old-password", "new-password"));

        assertEquals("user was updated concurrently; retry", exception.getMessage());
        verify(consoleSessionService, never()).revokeAll("alice");
    }

    @Test
    void shouldStillStripTheDomainSuffixOnTheDirectoryDoor() {
        when(directoryAuthenticator.available()).thenReturn(true);
        when(adminUserMapper.selectOne(any())).thenReturn(null);
        when(directoryAuthenticator.bind("alice", "pw"))
                .thenReturn(DirectoryBindOutcome.failure(DirectoryBindResult.INVALID_CREDENTIALS));

        assertThrows(BizException.class,
                () -> service.login(" Alice@Corp.Example.com ", "pw", LoginMode.SSO, IP));

        verify(directoryAuthenticator).bind("alice", "pw");
        assertEquals("alice", audits.get(0).getUsername());
    }

    @Test
    void shouldNotCountDirectoryOutagesTowardsTheLock() {
        when(directoryAuthenticator.available()).thenReturn(true);
        when(adminUserMapper.selectOne(any())).thenReturn(null);
        when(directoryAuthenticator.bind(anyString(), anyString())).thenReturn(
                DirectoryBindOutcome.failure(DirectoryBindResult.SERVICE_UNAVAILABLE));

        for (int attempt = 0; attempt < 10; attempt++) {
            BizException exception = assertThrows(BizException.class,
                    () -> service.login("alice", "pw", LoginMode.SSO, IP));
            assertEquals("directory is unavailable, try again later", exception.getMessage());
        }

        assertEquals(0, countedFailures.get());
        assertEquals(10, audits.size());
        assertTrue(audits.stream().allMatch(audit -> audit.getReason() == LoginResult.DIRECTORY_UNAVAILABLE));
        verify(directoryAuthenticator, times(10)).bind("alice", "pw");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldAuditLockedRetriesWithoutExtendingTheLockWindow() {
        when(loginAuditMapper.selectCount(any())).thenReturn(5L);

        BizException exception = assertThrows(BizException.class,
                () -> service.login("alice", "pw", LoginMode.LOCAL, IP));

        assertEquals(LOCKED_FAILURE, exception.getMessage());
        assertEquals(LoginResult.ACCOUNT_LOCKED, audits.get(0).getReason());
        ArgumentCaptor<LambdaQueryWrapper<LoginAudit>> wrapperCaptor =
                ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
        verify(loginAuditMapper).selectCount(wrapperCaptor.capture());
        LambdaQueryWrapper<LoginAudit> wrapper = wrapperCaptor.getValue();
        wrapper.getSqlSegment();
        assertTrue(wrapper.getParamNameValuePairs().containsValue(
                        LoginResult.DIRECTORY_UNAVAILABLE),
                () -> wrapper.getParamNameValuePairs().toString());
        assertTrue(wrapper.getParamNameValuePairs().containsValue(
                        LoginResult.ACCOUNT_LOCKED),
                () -> wrapper.getParamNameValuePairs().toString());
    }

    @Test
    void shouldStopConcurrentAttemptsAtTheThresholdForTheSameUsername() throws Exception {
        List<String> messages = runConcurrentAttempts(index -> "Alice@corp.example.com",
                index -> "198.51.100." + index);

        assertThreshold(messages);
    }

    @Test
    void shouldStopConcurrentAttemptsAtTheThresholdForTheSameAddress() throws Exception {
        List<String> messages = runConcurrentAttempts(index -> "user-" + index,
                index -> IP);

        assertThreshold(messages);
    }

    private List<String> runConcurrentAttempts(IntFunction<String> username,
                                                IntFunction<String> ip) throws Exception {
        when(adminUserMapper.selectOne(any())).thenReturn(user("alice", UserSource.LOCAL, UserStatus.ENABLED));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        ExecutorService executor = Executors.newFixedThreadPool(ATTEMPT_COUNT);
        CountDownLatch ready = new CountDownLatch(ATTEMPT_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int index = 0; index < ATTEMPT_COUNT; index++) {
                int attempt = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        service.login(username.apply(attempt), "bad", LoginMode.LOCAL, ip.apply(attempt));
                        return "unexpected success";
                    } catch (BizException exception) {
                        return exception.getMessage();
                    }
                }));
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();

            List<String> messages = new ArrayList<>();
            for (Future<String> future : futures) {
                messages.add(future.get(5, TimeUnit.SECONDS));
            }
            return messages;
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private void assertThreshold(List<String> messages) {
        assertEquals(5, messages.stream().filter(GENERIC_FAILURE::equals).count());
        assertEquals(ATTEMPT_COUNT - 5,
                messages.stream().filter(LOCKED_FAILURE::equals).count());
        verify(passwordEncoder, times(5)).matches("bad", "hash");
    }

    private void assertGenericFailure(Runnable attempt, LoginResult expectedReason) {
        BizException exception = assertThrows(BizException.class, attempt::run);
        assertEquals(GENERIC_FAILURE, exception.getMessage());
        assertEquals(expectedReason, audits.get(audits.size() - 1).getReason());
    }

    private AdminUser user(String username, UserSource source, UserStatus status) {
        AdminUser user = new AdminUser();
        user.setUserId("usr_1");
        user.setTenantId("tnt_1");
        user.setUsername(username);
        user.setSource(source);
        user.setStatus(status);
        user.setPasswordHash("hash");
        user.setMustChangePassword(0);
        return user;
    }
}
