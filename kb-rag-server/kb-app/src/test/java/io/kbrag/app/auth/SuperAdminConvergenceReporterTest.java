package io.kbrag.app.auth;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.kbrag.app.support.MybatisLambdaCache;
import io.kbrag.domain.constant.BuiltinRoles;
import io.kbrag.domain.constant.BuiltinTenants;
import io.kbrag.domain.entity.AdminUser;
import io.kbrag.domain.entity.Role;
import io.kbrag.domain.entity.UserRole;
import io.kbrag.domain.enums.UserStatus;
import io.kbrag.domain.mapper.AdminUserMapper;
import io.kbrag.domain.mapper.RoleMapper;
import io.kbrag.domain.mapper.UserRoleMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the super administrator convergence report of the M16 contract section 4.2: the expected
 * steady state of zero or one enabled holder passes silently, anything above it produces one log
 * that names every holder - the log is the whole product of this runner, so the test listens
 * to it directly through a list appender instead of inferring it from mapper traffic.
 *
 * <p>Every tenant is checked, not one of them: since V17 the role code is unique per tenant, so
 * {@code SUPER_ADMIN} names one role in each tenant and inspecting a single arbitrary one would leave
 * the over-privileged accounts of every other tenant unreported.
 *
 * @author owlzhangfq@gmail.com
 */
class SuperAdminConvergenceReporterTest {

    private static final String ROLE_ID = "role_super";
    private static final String OTHER_ROLE_ID = "role_super_acme";
    private static final String OTHER_TENANT_ID = "tnt_acme0000000001";

    private RoleMapper roleMapper;
    private UserRoleMapper userRoleMapper;
    private AdminUserMapper adminUserMapper;
    private SuperAdminConvergenceReporter reporter;
    private ListAppender<ILoggingEvent> logWatcher;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.register(Role.class, UserRole.class, AdminUser.class);
        roleMapper = mock(RoleMapper.class);
        userRoleMapper = mock(UserRoleMapper.class);
        adminUserMapper = mock(AdminUserMapper.class);
        reporter = new SuperAdminConvergenceReporter(roleMapper, userRoleMapper, adminUserMapper);
        logWatcher = new ListAppender<>();
        logWatcher.start();
        ((Logger) LoggerFactory.getLogger(SuperAdminConvergenceReporter.class)).addAppender(logWatcher);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(SuperAdminConvergenceReporter.class)).detachAppender(logWatcher);
    }

    @Test
    void shouldStaySilentWhenTheRoleDoesNotExist() {
        when(roleMapper.selectList(any())).thenReturn(List.of());

        reporter.run(null);

        verify(userRoleMapper, never()).selectList(any());
        assertEquals(0, reportCount());
    }

    @Test
    void shouldStaySilentWhenNobodyHoldsTheRole() {
        when(roleMapper.selectList(any())).thenReturn(List.of(superAdminRole()));
        when(userRoleMapper.selectList(any())).thenReturn(List.of());

        reporter.run(null);

        verify(adminUserMapper, never()).selectList(any());
        assertEquals(0, reportCount());
    }

    @Test
    void shouldStaySilentWhenExactlyOneEnabledAccountHoldsTheRole() {
        when(roleMapper.selectList(any())).thenReturn(List.of(superAdminRole()));
        when(userRoleMapper.selectList(any())).thenReturn(List.of(grant(ROLE_ID, "u_1")));
        when(adminUserMapper.selectList(any())).thenReturn(List.of(holder("u_1", "admin")));

        reporter.run(null);

        // One holder is least privilege already reached - a warning here would train the operator
        // to ignore the one that matters.
        assertEquals(0, reportCount());
    }

    @Test
    void shouldReportAndNameEveryHolderWhenMoreThanOneAccountHoldsTheRole() {
        when(roleMapper.selectList(any())).thenReturn(List.of(superAdminRole()));
        when(userRoleMapper.selectList(any())).thenReturn(List.of(
                grant(ROLE_ID, "u_1"), grant(ROLE_ID, "u_2")));
        when(adminUserMapper.selectList(any())).thenReturn(List.of(
                holder("u_1", "admin"), holder("u_2", "alice")));

        reporter.run(null);

        assertEquals(1, reportCount());
        String message = logWatcher.list.get(0).getFormattedMessage();
        // Naming the accounts is the point: a count alone would send the operator hunting through
        // the user list for what the log already knew.
        assertTrue(message.contains("admin"));
        assertTrue(message.contains("alice"));
    }

    @Test
    void shouldReportEveryTenantHoldingTheSameRoleCode() {
        // V17 dropped the global unique key on the role code, so the same code names one role per
        // tenant. A report that stopped at the first one would be silent about every other tenant.
        when(roleMapper.selectList(any())).thenReturn(List.of(superAdminRole(), otherTenantRole()));
        when(userRoleMapper.selectList(any()))
                .thenReturn(List.of(grant(ROLE_ID, "u_1"), grant(ROLE_ID, "u_2")))
                .thenReturn(List.of(grant(OTHER_ROLE_ID, "u_3"), grant(OTHER_ROLE_ID, "u_4")));
        when(adminUserMapper.selectList(any()))
                .thenReturn(List.of(holder("u_1", "admin"), holder("u_2", "alice")))
                .thenReturn(List.of(holder("u_3", "acme-admin"), holder("u_4", "bob")));

        reporter.run(null);

        assertEquals(2, reportCount());
        // The tenant is named so the operator knows which user management screen to open.
        assertTrue(logWatcher.list.get(0).getFormattedMessage()
                .contains(BuiltinTenants.DEFAULT_TENANT_ID));
        assertTrue(logWatcher.list.get(1).getFormattedMessage().contains(OTHER_TENANT_ID));
        assertTrue(logWatcher.list.get(1).getFormattedMessage().contains("acme-admin"));
    }

    private long reportCount() {
        return logWatcher.list.stream().filter(event -> event.getLevel() == Level.ERROR).count();
    }

    private Role superAdminRole() {
        return role(ROLE_ID, BuiltinTenants.DEFAULT_TENANT_ID);
    }

    private Role otherTenantRole() {
        return role(OTHER_ROLE_ID, OTHER_TENANT_ID);
    }

    private Role role(String roleId, String tenantId) {
        Role role = new Role();
        role.setRoleId(roleId);
        role.setTenantId(tenantId);
        role.setCode(BuiltinRoles.SUPER_ADMIN);
        return role;
    }

    private UserRole grant(String roleId, String userId) {
        UserRole grant = new UserRole();
        grant.setUserId(userId);
        grant.setRoleId(roleId);
        return grant;
    }

    private AdminUser holder(String userId, String username) {
        AdminUser user = new AdminUser();
        user.setUserId(userId);
        user.setUsername(username);
        user.setStatus(UserStatus.ENABLED);
        return user;
    }
}
