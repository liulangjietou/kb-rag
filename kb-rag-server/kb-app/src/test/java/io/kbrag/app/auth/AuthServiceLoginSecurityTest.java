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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
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
        when(loginAuditMapper.insert(any(LoginAudit.class))).thenAnswer(invocation -> {
            LoginAudit audit = invocation.getArgument(0);
            audits.add(audit);
            if (Integer.valueOf(0).equals(audit.getSuccess())
                    && audit.getReason() != LoginResult.DIRECTORY_UNAVAILABLE) {
                countedFailures.incrementAndGet();
            }
            return 1;
        });

        TokenStore tokenStore = mock(TokenStore.class);
        LoginSuccessService loginSuccessService = new LoginSuccessService(
                adminUserMapper, loginAuditMapper, tokenStore, mock(UserService.class),
                mock(DirectoryGroupSyncService.class), mock(PrincipalResolver.class));
        service = new AuthService(adminUserMapper, loginAuditMapper, tenantMapper,
                tokenStore, new KbProperties(), passwordEncoder, directoryAuthenticator,
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
        verify(adminUserMapper, times(5)).selectOne(any());
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
