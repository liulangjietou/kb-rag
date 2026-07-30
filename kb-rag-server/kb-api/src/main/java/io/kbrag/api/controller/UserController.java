package io.kbrag.api.controller;

import io.kbrag.api.annotation.AuditedOperation;
import io.kbrag.api.annotation.RequiresPermission;
import io.kbrag.api.dto.AssignRolesRequest;
import io.kbrag.api.dto.CreateUserRequest;
import io.kbrag.api.dto.MoveUserTenantRequest;
import io.kbrag.api.dto.PageResponse;
import io.kbrag.api.dto.ResetUserPasswordRequest;
import io.kbrag.api.dto.UpdateUserRequest;
import io.kbrag.api.dto.UpdateUserStatusRequest;
import io.kbrag.api.dto.UserResponse;
import io.kbrag.app.auth.UserService;
import io.kbrag.app.auth.AccessGuard;
import io.kbrag.common.api.Result;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.constant.PermissionCodes;
import io.kbrag.domain.entity.AdminUser;
import io.kbrag.domain.enums.UserSource;
import io.kbrag.domain.enums.UserStatus;
import com.baomidou.mybatisplus.core.metadata.IPage;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Console user administration endpoints.
 *
 * <p>Guarded as a whole by {@code user:manage}: every method here can hand somebody else access, so there is
 * no read only audience for this screen that would justify a second, weaker code.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@RequiresPermission(PermissionCodes.USER_MANAGE)
public class UserController {

    private final UserService userService;

    /**
     * Lists accounts, newest first.
     *
     * @param keyword optional fragment matched against login name and display name
     * @param status  optional lifecycle filter
     * @param source  optional origin filter
     * @param page    one based page number
     * @param size    page size
     * @return page of accounts
     */
    @GetMapping
    public Result<PageResponse<UserResponse>> list(@RequestParam(required = false) String keyword,
                                                   @RequestParam(required = false) String status,
                                                   @RequestParam(required = false) String source,
                                                   @RequestParam(defaultValue = "1") long page,
                                                   @RequestParam(defaultValue = "20") long size) {
        IPage<AdminUser> found = userService.list(keyword, parseStatus(status), parseSource(source), page, size);
        // One query for the role names of the whole page, rather than one per row.
        Map<String, List<String>> roleNames = userService.roleNamesOf(
                found.getRecords().stream().map(AdminUser::getUserId).toList());
        return Result.success(PageResponse.from(found,
                user -> UserResponse.from(user, null, roleNames.get(user.getUserId()))));
    }

    /**
     * Loads one account together with the roles it holds.
     *
     * @param userId user business id
     * @return account view
     */
    @GetMapping("/{userId}")
    public Result<UserResponse> get(@PathVariable String userId) {
        AdminUser user = userService.get(userId);
        return Result.success(UserResponse.from(user, userService.roleIdsOf(userId), null));
    }

    /**
     * Creates a local account with an initial password that has to be rotated at first login.
     *
     * <p>Naming a tenant is reserved to the platform operator: it is how the first account of a
     * fresh tenant comes into being, and nobody else has a reason to create accounts outside their
     * own tenant.
     *
     * @param request account payload
     * @return created account
     */
    @PostMapping
    @AuditedOperation(module = "USER", action = "CREATE", targetType = "USER",
            targetId = "#result.data.userId")
    public Result<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        if (request.tenantId() != null && !request.tenantId().isBlank()) {
            AccessGuard.requirePermission(PermissionCodes.TENANT_MANAGE);
        }
        AdminUser user = userService.create(request.username(), request.displayName(), request.email(),
                request.password(), request.roleIds(), request.tenantId());
        return Result.success(UserResponse.from(user, userService.roleIdsOf(user.getUserId()), null));
    }

    /**
     * Updates the display fields of an account.
     *
     * @param userId  user business id
     * @param request editable fields
     * @return updated account
     */
    @PutMapping("/{userId}")
    @AuditedOperation(module = "USER", action = "UPDATE", targetType = "USER", targetId = "#userId")
    public Result<UserResponse> update(@PathVariable String userId,
                                       @Valid @RequestBody UpdateUserRequest request) {
        userService.update(userId, request.displayName(), request.email());
        AdminUser user = userService.get(userId);
        return Result.success(UserResponse.from(user, userService.roleIdsOf(userId), null));
    }

    /**
     * Enables or suspends an account; suspending ends its sessions immediately.
     *
     * @param userId  user business id
     * @param request new lifecycle state
     * @return empty success envelope
     */
    @PutMapping("/{userId}/status")
    @AuditedOperation(module = "USER", action = "UPDATE_STATUS", targetType = "USER", targetId = "#userId")
    public Result<Void> updateStatus(@PathVariable String userId,
                                     @Valid @RequestBody UpdateUserStatusRequest request) {
        UserStatus status = parseStatus(request.status());
        if (status == null) {
            throw BizException.invalidParam("status 仅支持 ENABLED 或 DISABLED");
        }
        userService.updateStatus(userId, status);
        return Result.success(null);
    }

    /**
     * Replaces the roles held by an account.
     *
     * @param userId  user business id
     * @param request complete new role set
     * @return empty success envelope
     */
    @PutMapping("/{userId}/roles")
    @AuditedOperation(module = "USER", action = "ASSIGN_ROLES", targetType = "USER", targetId = "#userId")
    public Result<Void> assignRoles(@PathVariable String userId,
                                    @Valid @RequestBody AssignRolesRequest request) {
        userService.assignRoles(userId, request.roleIds());
        return Result.success(null);
    }

    /**
     * Moves an account into another tenant, dropping its role bindings.
     *
     * <p>Held to {@code tenant:manage} instead of the class level code: crossing a tenant boundary
     * is a platform operator action, not day to day user administration.
     *
     * @param userId  user business id
     * @param request target tenant
     * @return empty success envelope
     */
    @PutMapping("/{userId}/tenant")
    @RequiresPermission(PermissionCodes.TENANT_MANAGE)
    @AuditedOperation(module = "USER", action = "MOVE_TENANT", targetType = "USER", targetId = "#userId")
    public Result<Void> moveTenant(@PathVariable String userId,
                                   @Valid @RequestBody MoveUserTenantRequest request) {
        userService.moveTenant(userId, request.tenantId());
        return Result.success(null);
    }

    /**
     * Resets the password of a local account and ends its sessions.
     *
     * @param userId  user business id
     * @param request replacement password
     * @return empty success envelope
     */
    @PostMapping("/{userId}/reset-password")
    @AuditedOperation(module = "USER", action = "RESET_PASSWORD", targetType = "USER", targetId = "#userId")
    public Result<Void> resetPassword(@PathVariable String userId,
                                      @Valid @RequestBody ResetUserPasswordRequest request) {
        userService.resetPassword(userId, request.newPassword());
        return Result.success(null);
    }

    /**
     * Deletes an account and its role bindings.
     *
     * @param userId user business id
     * @return empty success envelope
     */
    @DeleteMapping("/{userId}")
    @AuditedOperation(module = "USER", action = "DELETE", targetType = "USER", targetId = "#userId")
    public Result<Void> delete(@PathVariable String userId) {
        userService.delete(userId);
        return Result.success(null);
    }

    private UserStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return UserStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw BizException.invalidParam("status 仅支持 ENABLED 或 DISABLED");
        }
    }

    private UserSource parseSource(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        try {
            return UserSource.valueOf(source.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw BizException.invalidParam("source 仅支持 LOCAL 或 LDAP");
        }
    }
}
