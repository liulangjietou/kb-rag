package io.kbrag.api.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.kbrag.api.annotation.AuditedOperation;
import io.kbrag.api.annotation.RequiresPermission;
import io.kbrag.api.dto.ApproveRegistrationRequest;
import io.kbrag.api.dto.PageResponse;
import io.kbrag.api.dto.RegistrationReviewResponse;
import io.kbrag.api.dto.RejectRegistrationRequest;
import io.kbrag.app.auth.AccessGuard;
import io.kbrag.app.registration.RegistrationApproval;
import io.kbrag.app.registration.RegistrationReviewService;
import io.kbrag.common.api.Result;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.constant.PermissionCodes;
import io.kbrag.domain.entity.RegistrationApplication;
import io.kbrag.domain.enums.RegistrationApplicationStatus;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 需要 user:manage 与 tenant:manage 两项权限的注册审核接口。
 *
 * <p>类级注解先要求用户管理权限；每个方法再显式要求租户管理权限，因为多值注解语义是
 * 任一满足，不能用一个注解表达“同时满足”。
 *
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/registration-reviews")
@RequiredArgsConstructor
@RequiresPermission(PermissionCodes.USER_MANAGE)
public class RegistrationReviewController {

    private static final long MAX_PAGE_SIZE = 100L;

    private final RegistrationReviewService reviewService;

    /** 列出注册申请。 */
    @GetMapping
    public Result<PageResponse<RegistrationReviewResponse>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        AccessGuard.requirePermission(PermissionCodes.TENANT_MANAGE);
        requirePage(page, size);
        IPage<RegistrationApplication> found = reviewService.list(
                keyword, parseStatus(status), page, size);
        Map<String, List<String>> roleIds = reviewService.roleIdsByApplication(found.getRecords());
        return Result.success(PageResponse.from(found,
                application -> RegistrationReviewResponse.from(
                        application, roleIds.get(application.getApplicationId()))));
    }

    /** 原子分配租户和角色并通过申请。 */
    @PostMapping("/{applicationId}/approve")
    @AuditedOperation(module = "USER", action = "APPROVE_REGISTRATION",
            targetType = "REGISTRATION_APPLICATION", targetId = "#applicationId")
    public Result<RegistrationReviewResponse> approve(
            @PathVariable String applicationId,
            @Valid @RequestBody ApproveRegistrationRequest request) {
        AccessGuard.requirePermission(PermissionCodes.TENANT_MANAGE);
        RegistrationApproval approved = reviewService.approve(applicationId,
                request.tenantId(), request.roleIds(), AccessGuard.currentUser().userId());
        return Result.success(RegistrationReviewResponse.from(
                approved.application(), approved.roleIds()));
    }

    /** 拒绝申请并记录必填原因。 */
    @PostMapping("/{applicationId}/reject")
    @AuditedOperation(module = "USER", action = "REJECT_REGISTRATION",
            targetType = "REGISTRATION_APPLICATION", targetId = "#applicationId")
    public Result<RegistrationReviewResponse> reject(
            @PathVariable String applicationId,
            @Valid @RequestBody RejectRegistrationRequest request) {
        AccessGuard.requirePermission(PermissionCodes.TENANT_MANAGE);
        RegistrationApplication rejected = reviewService.reject(applicationId,
                request.reason(), AccessGuard.currentUser().userId());
        return Result.success(RegistrationReviewResponse.from(rejected, List.of()));
    }

    private RegistrationApplicationStatus parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return RegistrationApplicationStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw BizException.invalidParam("status only supports PENDING, APPROVED or REJECTED");
        }
    }

    private void requirePage(long page, long size) {
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw BizException.invalidParam("page must be positive and size must be between 1 and 100");
        }
    }
}
