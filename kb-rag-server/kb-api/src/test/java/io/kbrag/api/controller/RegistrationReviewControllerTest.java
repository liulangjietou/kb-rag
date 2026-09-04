package io.kbrag.api.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.kbrag.api.annotation.RequiresPermission;
import io.kbrag.api.dto.ApproveRegistrationRequest;
import io.kbrag.api.dto.RejectRegistrationRequest;
import io.kbrag.app.registration.RegistrationApproval;
import io.kbrag.app.registration.RegistrationReviewService;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.constant.PermissionCodes;
import io.kbrag.domain.context.UserContextHolder;
import io.kbrag.domain.entity.RegistrationApplication;
import io.kbrag.domain.enums.RegistrationApplicationStatus;
import io.kbrag.domain.enums.UserSource;
import io.kbrag.domain.model.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 固化注册审核端点必须同时持有 USER_MANAGE 与 TENANT_MANAGE。
 *
 * @author owlzhangfq@gmail.com
 */
class RegistrationReviewControllerTest {

    private final RegistrationReviewService service = mock(RegistrationReviewService.class);
    private final RegistrationReviewController controller = new RegistrationReviewController(service);

    @AfterEach
    void clearContext() {
        UserContextHolder.clear();
    }

    @Test
    void shouldDeclareUserManageAtTheControllerBoundary() {
        RequiresPermission annotation =
                RegistrationReviewController.class.getAnnotation(RequiresPermission.class);

        assertArrayEquals(new String[]{PermissionCodes.USER_MANAGE}, annotation.value());
    }

    @Test
    void shouldRejectEveryReviewOperationWhenTenantManageIsMissing() {
        UserContextHolder.set(principal(Set.of(PermissionCodes.USER_MANAGE)));

        assertThrows(BizException.class, () -> controller.list(null, null, 1, 20));
        assertThrows(BizException.class, () -> controller.approve("reg_1",
                new ApproveRegistrationRequest("tnt_1", List.of("role_1"))));
        assertThrows(BizException.class, () -> controller.reject("reg_1",
                new RejectRegistrationRequest("missing information")));

        verifyNoInteractions(service);
    }

    @Test
    void shouldReachAllReviewServicesOnlyWhenBothPermissionsArePresent() {
        UserContextHolder.set(principal(Set.of(
                PermissionCodes.USER_MANAGE, PermissionCodes.TENANT_MANAGE)));
        Page<RegistrationApplication> page = new Page<>(1, 20, 0);
        page.setRecords(List.of());
        when(service.list(null, null, 1, 20)).thenReturn(page);
        when(service.roleIdsByApplication(any())).thenReturn(Map.of("reg_1", List.of("role_1")));
        RegistrationApplication application = application();
        when(service.approve("reg_1", "tnt_1", List.of("role_1"), "usr_reviewer"))
                .thenReturn(new RegistrationApproval(application, List.of("role_1")));
        when(service.reject("reg_1", "missing information", "usr_reviewer"))
                .thenReturn(application);

        controller.list(null, null, 1, 20);
        controller.approve("reg_1", new ApproveRegistrationRequest("tnt_1", List.of("role_1")));
        controller.reject("reg_1", new RejectRegistrationRequest("missing information"));

        verify(service).list(null, null, 1, 20);
        verify(service).approve("reg_1", "tnt_1", List.of("role_1"), "usr_reviewer");
        verify(service).reject("reg_1", "missing information", "usr_reviewer");
        verify(service, times(1)).roleIdsByApplication(any());
    }

    @Test
    void shouldReturnTransactionRolesWithoutPostCommitLookup() {
        UserContextHolder.set(principal(Set.of(
                PermissionCodes.USER_MANAGE, PermissionCodes.TENANT_MANAGE)));
        RegistrationApplication application = application();
        when(service.approve("reg_1", "tnt_1", List.of(" role_1 ", "role_1"), "usr_reviewer"))
                .thenReturn(new RegistrationApproval(application, List.of("role_1")));

        io.kbrag.common.api.Result<io.kbrag.api.dto.RegistrationReviewResponse> response =
                controller.approve("reg_1",
                        new ApproveRegistrationRequest("tnt_1", List.of(" role_1 ", "role_1")));

        assertEquals(List.of("role_1"), response.getData().roleIds());
        verify(service, never()).roleIdsByApplication(any());
    }

    private UserPrincipal principal(Set<String> permissions) {
        return new UserPrincipal("usr_reviewer", "tnt_default0000000", "reviewer", "Reviewer",
                UserSource.LOCAL, Set.of(), Set.of(), permissions, true, Set.of());
    }

    private RegistrationApplication application() {
        RegistrationApplication application = new RegistrationApplication();
        application.setApplicationId("reg_1");
        application.setEmail("person@example.com");
        application.setDisplayName("Alice");
        application.setStatus(RegistrationApplicationStatus.APPROVED);
        application.setApprovedTenantId("tnt_1");
        application.setApprovedUserId("usr_created");
        return application;
    }
}
