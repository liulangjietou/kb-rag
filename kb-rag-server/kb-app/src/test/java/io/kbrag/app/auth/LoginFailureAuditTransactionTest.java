package io.kbrag.app.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.app.support.MybatisLambdaCache;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.AdminUser;
import io.kbrag.domain.entity.LoginAudit;
import io.kbrag.domain.entity.Tenant;
import io.kbrag.domain.enums.LoginMode;
import io.kbrag.domain.enums.UserSource;
import io.kbrag.domain.enums.UserStatus;
import io.kbrag.domain.mapper.AdminUserMapper;
import io.kbrag.domain.mapper.LoginAuditMapper;
import io.kbrag.domain.mapper.TenantMapper;
import io.kbrag.domain.port.DirectoryAuthenticator;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LoginFailureAuditTransactionTest {

    @Test
    void shouldCommitFailureAuditWhenAuthenticationTransactionRollsBack() {
        MybatisLambdaCache.register(AdminUser.class, LoginAudit.class, Tenant.class);
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(TransactionTestConfig.class)) {
            AuthService authService = context.getBean(AuthService.class);

            assertThrows(BizException.class,
                    () -> authService.login("Alice@corp.example.com", "bad", LoginMode.LOCAL,
                            "203.0.113.7"));

            JdbcTemplate jdbcTemplate = context.getBean(JdbcTemplate.class);
            assertEquals(1, jdbcTemplate.queryForObject(
                    "select count(*) from t_kb_login_audit", Integer.class));
            assertEquals("alice", jdbcTemplate.queryForObject(
                    "select username from t_kb_login_audit", String.class));
            assertEquals("BAD_PASSWORD", jdbcTemplate.queryForObject(
                    "select reason from t_kb_login_audit", String.class));
        }
    }

    @Test
    void shouldRollbackSuccessAuditWhenSessionIssuingFails() {
        MybatisLambdaCache.register(AdminUser.class, LoginAudit.class, Tenant.class);
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(TransactionTestConfig.class)) {
            BCryptPasswordEncoder passwordEncoder = context.getBean(BCryptPasswordEncoder.class);
            when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
            ConsoleSessionService consoleSessionService = context.getBean(ConsoleSessionService.class);
            when(consoleSessionService.issue("alice")).thenThrow(new IllegalStateException("token store failed"));

            AuthService authService = context.getBean(AuthService.class);
            assertThrows(IllegalStateException.class,
                    () -> authService.login("alice", "pw", LoginMode.LOCAL, "203.0.113.7"));

            JdbcTemplate jdbcTemplate = context.getBean(JdbcTemplate.class);
            assertEquals(0, jdbcTemplate.queryForObject(
                    "select count(*) from t_kb_login_audit", Integer.class));
        }
    }

    @Test
    void shouldRejectCallingTheGuardedEntryPointInsideAnExistingTransaction() {
        MybatisLambdaCache.register(AdminUser.class, LoginAudit.class, Tenant.class);
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(TransactionTestConfig.class)) {
            AuthService authService = context.getBean(AuthService.class);
            TransactionTemplate transaction = new TransactionTemplate(
                    context.getBean(PlatformTransactionManager.class));

            assertThrows(IllegalTransactionStateException.class,
                    () -> transaction.executeWithoutResult(ignored ->
                            authService.login("alice", "bad", LoginMode.LOCAL, "203.0.113.7")));

            assertEquals(0, context.getBean(JdbcTemplate.class).queryForObject(
                    "select count(*) from t_kb_login_audit", Integer.class));
        }
    }

    @Test
    void shouldCommitEachFailureBeforeTheNextConcurrentAttemptChecksTheThreshold() throws Exception {
        MybatisLambdaCache.register(AdminUser.class, LoginAudit.class, Tenant.class);
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(TransactionTestConfig.class)) {
            AuthService authService = context.getBean(AuthService.class);
            int attempts = 12;
            ExecutorService executor = Executors.newFixedThreadPool(attempts);
            CountDownLatch ready = new CountDownLatch(attempts);
            CountDownLatch start = new CountDownLatch(1);
            try {
                List<Future<String>> futures = new ArrayList<>();
                for (int index = 0; index < attempts; index++) {
                    int suffix = index;
                    futures.add(executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        try {
                            authService.login("Alice@corp.example.com", "bad", LoginMode.LOCAL,
                                    "198.51.100." + suffix);
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
                assertEquals(5, messages.stream()
                        .filter("invalid username or password"::equals).count());
                assertEquals(attempts - 5, messages.stream()
                        .filter(message -> message.startsWith("account temporarily locked"))
                        .count());
            } finally {
                start.countDown();
                executor.shutdownNow();
            }

            JdbcTemplate jdbcTemplate = context.getBean(JdbcTemplate.class);
            assertEquals(5, jdbcTemplate.queryForObject(
                    "select count(*) from t_kb_login_audit where reason = 'BAD_PASSWORD'",
                    Integer.class));
            assertEquals(attempts - 5, jdbcTemplate.queryForObject(
                    "select count(*) from t_kb_login_audit where reason = 'ACCOUNT_LOCKED'",
                    Integer.class));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionTestConfig {

        @Bean
        DataSource dataSource() {
            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setDriverClassName("org.h2.Driver");
            dataSource.setUrl("jdbc:h2:mem:login_audit_tx;MODE=MySQL;DB_CLOSE_DELAY=-1");
            dataSource.setUsername("sa");
            dataSource.setPassword("");
            return dataSource;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
            jdbcTemplate.execute("drop table if exists t_kb_login_audit");
            jdbcTemplate.execute("create table t_kb_login_audit ("
                    + "id bigint generated by default as identity primary key, "
                    + "username varchar(128) not null, ip varchar(64), success int not null, "
                    + "reason varchar(64) not null)");
            return jdbcTemplate;
        }

        @Bean
        LoginAuditMapper loginAuditMapper(JdbcTemplate jdbcTemplate) {
            LoginAuditMapper mapper = mock(LoginAuditMapper.class);
            when(mapper.selectOne(any())).thenReturn(null);
            when(mapper.selectCount(any())).thenAnswer(ignored -> jdbcTemplate.queryForObject(
                    "select count(*) from t_kb_login_audit where success = 0 "
                            + "and reason not in ('DIRECTORY_UNAVAILABLE', 'ACCOUNT_LOCKED')",
                    Long.class));
            when(mapper.insert(any(LoginAudit.class))).thenAnswer(invocation -> {
                LoginAudit audit = invocation.getArgument(0);
                return jdbcTemplate.update("insert into t_kb_login_audit "
                                + "(username, ip, success, reason) values (?, ?, ?, ?)",
                        audit.getUsername(), audit.getIp(), audit.getSuccess(), audit.getReason().name());
            });
            return mapper;
        }

        @Bean
        LoginFailureAuditService loginFailureAuditService(LoginAuditMapper loginAuditMapper) {
            return new LoginFailureAuditService(loginAuditMapper);
        }

        @Bean
        LoginAttemptGuard loginAttemptGuard() {
            return new LoginAttemptGuard();
        }

        @Bean
        @SuppressWarnings({"rawtypes", "unchecked"})
        AdminUserMapper adminUserMapper() {
            AdminUserMapper mapper = mock(AdminUserMapper.class);
            when(mapper.selectOne(any())).thenAnswer(invocation -> {
                LambdaQueryWrapper<AdminUser> wrapper = invocation.getArgument(0);
                wrapper.getSqlSegment();
                return wrapper.getParamNameValuePairs().containsValue("alice")
                        ? localUser() : null;
            });
            when(mapper.updateById(any(AdminUser.class))).thenReturn(1);
            return mapper;
        }

        @Bean
        TenantMapper tenantMapper() {
            return mock(TenantMapper.class);
        }

        @Bean
        ConsoleSessionService consoleSessionService() {
            return mock(ConsoleSessionService.class);
        }

        @Bean
        BCryptPasswordEncoder passwordEncoder() {
            BCryptPasswordEncoder passwordEncoder = mock(BCryptPasswordEncoder.class);
            when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);
            return passwordEncoder;
        }

        @Bean
        DirectoryAuthenticator directoryAuthenticator() {
            return mock(DirectoryAuthenticator.class);
        }

        @Bean
        DirectoryGroupSyncService directoryGroupSyncService() {
            return mock(DirectoryGroupSyncService.class);
        }

        @Bean
        UserService userService() {
            return mock(UserService.class);
        }

        @Bean
        PrincipalResolver principalResolver() {
            return mock(PrincipalResolver.class);
        }

        @Bean
        LoginSuccessService loginSuccessService(AdminUserMapper adminUserMapper,
                                                LoginAuditMapper loginAuditMapper,
                                                ConsoleSessionService consoleSessionService,
                                                UserService userService,
                                                DirectoryGroupSyncService directoryGroupSyncService,
                                                PrincipalResolver principalResolver) {
            return new LoginSuccessService(adminUserMapper, loginAuditMapper, consoleSessionService,
                    userService, directoryGroupSyncService, principalResolver);
        }

        @Bean
        AuthService authService(AdminUserMapper adminUserMapper,
                                LoginAuditMapper loginAuditMapper,
                                TenantMapper tenantMapper,
                                ConsoleSessionService consoleSessionService,
                                BCryptPasswordEncoder passwordEncoder,
                                DirectoryAuthenticator directoryAuthenticator,
                                LoginFailureAuditService loginFailureAuditService,
                                LoginSuccessService loginSuccessService,
                                LoginAttemptGuard loginAttemptGuard) {
            return new AuthService(adminUserMapper, loginAuditMapper, tenantMapper,
                    consoleSessionService, new KbProperties(), passwordEncoder, directoryAuthenticator,
                    loginFailureAuditService, loginSuccessService, loginAttemptGuard);
        }

        private AdminUser localUser() {
            AdminUser user = new AdminUser();
            user.setUserId("usr_1");
            user.setTenantId("tnt_1");
            user.setUsername("alice");
            user.setSource(UserSource.LOCAL);
            user.setStatus(UserStatus.ENABLED);
            user.setPasswordHash("hash");
            return user;
        }
    }
}
