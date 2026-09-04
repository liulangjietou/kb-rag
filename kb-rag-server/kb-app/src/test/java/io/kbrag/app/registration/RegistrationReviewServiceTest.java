package io.kbrag.app.registration;

import io.kbrag.app.auth.UserService;
import io.kbrag.app.support.MybatisLambdaCache;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.entity.AdminUser;
import io.kbrag.domain.entity.MailOutbox;
import io.kbrag.domain.entity.RegistrationApplication;
import io.kbrag.domain.entity.RegistrationApplicationRole;
import io.kbrag.domain.entity.Role;
import io.kbrag.domain.entity.RolePermission;
import io.kbrag.domain.entity.Tenant;
import io.kbrag.domain.enums.RegistrationApplicationStatus;
import io.kbrag.domain.enums.TenantStatus;
import io.kbrag.domain.mapper.MailOutboxMapper;
import io.kbrag.domain.mapper.RegistrationApplicationMapper;
import io.kbrag.domain.mapper.RegistrationApplicationRoleMapper;
import io.kbrag.domain.mapper.RoleMapper;
import io.kbrag.domain.mapper.RolePermissionMapper;
import io.kbrag.domain.mapper.TenantMapper;
import io.kbrag.domain.port.NotificationMailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 固化审批的租户角色原子边界、状态冲突与审核结果 outbox。
 *
 * @author owlzhangfq@gmail.com
 */
class RegistrationReviewServiceTest {

    private static final String APPLICATION_ID = "reg_1";
    private static final String TENANT_ID = "tnt_1";
    private static final String ROLE_ID = "role_1";
    private static final String REVIEWER_ID = "usr_reviewer";

    private final RegistrationApplicationMapper applicationMapper = mock(RegistrationApplicationMapper.class);
    private final RegistrationApplicationRoleMapper applicationRoleMapper =
            mock(RegistrationApplicationRoleMapper.class);
    private final TenantMapper tenantMapper = mock(TenantMapper.class);
    private final RoleMapper roleMapper = mock(RoleMapper.class);
    private final RolePermissionMapper rolePermissionMapper = mock(RolePermissionMapper.class);
    private final MailOutboxMapper outboxMapper = mock(MailOutboxMapper.class);
    private final NotificationMailSender mailSender = mock(NotificationMailSender.class);
    private final UserService userService = mock(UserService.class);
    private final RegistrationReviewService service = new RegistrationReviewService(
            applicationMapper, applicationRoleMapper, tenantMapper, roleMapper, rolePermissionMapper,
            outboxMapper, mailSender, userService);
    private RegistrationApplication application;
    private Role role;
    private Tenant tenant;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.register(RegistrationApplication.class, Tenant.class, Role.class,
                RolePermission.class, RegistrationApplicationRole.class);
        application = new RegistrationApplication();
        application.setApplicationId(APPLICATION_ID);
        application.setEmail("person@example.com");
        application.setDisplayName("Alice");
        application.setPasswordHash("$2a$10$tesHlq8Mb9Tj200DjJwf6Om6ibfxNmblzIJp2uQ0goV2Qmm0uCt4W");
        application.setStatus(RegistrationApplicationStatus.PENDING);
        when(applicationMapper.selectByApplicationIdForUpdate(APPLICATION_ID)).thenReturn(application);

        tenant = new Tenant();
        tenant.setTenantId(TENANT_ID);
        tenant.setStatus(TenantStatus.ENABLED);
        when(tenantMapper.selectOne(any())).thenReturn(tenant);

        role = new Role();
        role.setRoleId(ROLE_ID);
        role.setTenantId(TENANT_ID);
        when(roleMapper.selectList(any())).thenReturn(List.of(role));
        when(rolePermissionMapper.selectList(any())).thenReturn(List.of());
        when(applicationRoleMapper.insert(any(RegistrationApplicationRole.class))).thenReturn(1);
        when(outboxMapper.insert(any(MailOutbox.class))).thenReturn(1);
        when(mailSender.loginUrl()).thenReturn("http://localhost:20002/login");
    }

    @Test
    void shouldAtomicallyCreateAccountApproveAndQueueNotification() {
        AdminUser user = new AdminUser();
        user.setUserId("usr_created");
        when(userService.createRegisteredUser(any(), any(), any(), any(), any())).thenReturn(user);
        when(applicationMapper.markApproved(any(), any(), any(), any(), any())).thenReturn(1);

        RegistrationApproval approved = service.approve(
                APPLICATION_ID, TENANT_ID, List.of(ROLE_ID), REVIEWER_ID);

        assertEquals(RegistrationApplicationStatus.APPROVED, approved.application().getStatus());
        assertEquals("usr_created", approved.application().getApprovedUserId());
        assertNull(approved.application().getPasswordHash());
        assertEquals(List.of(ROLE_ID), approved.roleIds());
        verify(userService).createRegisteredUser("person@example.com", "Alice",
                "$2a$10$tesHlq8Mb9Tj200DjJwf6Om6ibfxNmblzIJp2uQ0goV2Qmm0uCt4W",
                List.of(ROLE_ID), TENANT_ID);
        ArgumentCaptor<MailOutbox> outbox = ArgumentCaptor.forClass(MailOutbox.class);
        verify(outboxMapper).insert(outbox.capture());
        assertEquals("person@example.com", outbox.getValue().getRecipient());
        assertEquals(0, outbox.getValue().getRetryCount());
        ArgumentCaptor<RegistrationApplicationRole> snapshot =
                ArgumentCaptor.forClass(RegistrationApplicationRole.class);
        verify(applicationRoleMapper).insert(snapshot.capture());
        assertEquals(APPLICATION_ID, snapshot.getValue().getApplicationId());
        assertEquals(ROLE_ID, snapshot.getValue().getRoleId());
    }

    @Test
    void shouldReturnAndPersistTheNormalizedDistinctRoles() {
        AdminUser user = new AdminUser();
        user.setUserId("usr_created");
        when(userService.createRegisteredUser(any(), any(), any(), any(), any())).thenReturn(user);
        when(applicationMapper.markApproved(any(), any(), any(), any(), any())).thenReturn(1);

        RegistrationApproval approval = service.approve(APPLICATION_ID, TENANT_ID,
                List.of(" " + ROLE_ID + " ", ROLE_ID), REVIEWER_ID);

        assertEquals(List.of(ROLE_ID), approval.roleIds());
        verify(userService).createRegisteredUser(any(), any(), any(),
                org.mockito.ArgumentMatchers.eq(List.of(ROLE_ID)), any());
    }

    @Test
    void shouldRejectDisabledTenantBeforeCreatingAccount() {
        tenant.setStatus(TenantStatus.DISABLED);

        assertThrows(BizException.class,
                () -> service.approve(APPLICATION_ID, TENANT_ID, List.of(ROLE_ID), REVIEWER_ID));

        verify(userService, never()).createRegisteredUser(any(), any(), any(), any(), any());
    }

    @Test
    void shouldRejectCrossTenantRoleBeforeCreatingAccount() {
        role.setTenantId("tnt_other");

        assertThrows(BizException.class,
                () -> service.approve(APPLICATION_ID, TENANT_ID, List.of(ROLE_ID), REVIEWER_ID));

        verify(userService, never()).createRegisteredUser(any(), any(), any(), any(), any());
        verify(outboxMapper, never()).insert(any(MailOutbox.class));
    }

    @Test
    void shouldRejectAConcurrentReviewConflictAndRollbackAccount() {
        AdminUser user = new AdminUser();
        user.setUserId("usr_created");
        when(userService.createRegisteredUser(any(), any(), any(), any(), any())).thenReturn(user);
        when(applicationMapper.markApproved(any(), any(), any(), any(), any())).thenReturn(0);

        assertThrows(BizException.class,
                () -> service.approve(APPLICATION_ID, TENANT_ID, List.of(ROLE_ID), REVIEWER_ID));

        verify(outboxMapper, never()).insert(any(MailOutbox.class));
    }

    @Test
    void shouldRequireRejectReasonAndQueueTheResult() {
        assertThrows(BizException.class,
                () -> service.reject(APPLICATION_ID, "  ", REVIEWER_ID));
        verify(applicationMapper, never()).markRejected(any(), any(), any(), any());

        when(applicationMapper.markRejected(any(), any(), any(), any())).thenReturn(1);
        RegistrationApplication rejected = service.reject(
                APPLICATION_ID, " Information incomplete ", REVIEWER_ID);

        assertEquals(RegistrationApplicationStatus.REJECTED, rejected.getStatus());
        assertEquals("Information incomplete", rejected.getReviewReason());
        assertNull(rejected.getPasswordHash());
        verify(outboxMapper).insert(any(MailOutbox.class));
    }

    @Test
    void shouldReadImmutableApplicationRoleSnapshotInsteadOfCurrentUserRoles() {
        application.setStatus(RegistrationApplicationStatus.APPROVED);
        application.setApprovedUserId("usr_created");
        RegistrationApplicationRole snapshot = new RegistrationApplicationRole();
        snapshot.setApplicationId(APPLICATION_ID);
        snapshot.setRoleId(ROLE_ID);
        when(applicationRoleMapper.selectList(any())).thenReturn(List.of(snapshot));

        Map<String, List<String>> roles = service.roleIdsByApplication(List.of(application));

        assertEquals(List.of(ROLE_ID), roles.get(APPLICATION_ID));
    }

    @Test
    void shouldFailTheReviewWhenItsNotificationCannotBePersisted() {
        when(applicationMapper.markRejected(any(), any(), any(), any())).thenReturn(1);
        when(outboxMapper.insert(any(MailOutbox.class))).thenReturn(0);

        assertThrows(BizException.class,
                () -> service.reject(APPLICATION_ID, "Information incomplete", REVIEWER_ID));
    }
}
