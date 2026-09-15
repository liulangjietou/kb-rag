package io.kbrag.api.controller;

import io.kbrag.api.annotation.AuditedOperation;
import io.kbrag.api.annotation.RequiresPermission;
import io.kbrag.api.dto.PermissionResponse;
import io.kbrag.api.dto.RoleResponse;
import io.kbrag.api.dto.RoleAppOptionResponse;
import io.kbrag.api.dto.SaveRoleRequest;
import io.kbrag.app.auth.AccessGuard;
import io.kbrag.app.auth.RoleAppScopeService;
import io.kbrag.app.auth.RoleService;
import io.kbrag.common.api.Result;
import io.kbrag.domain.constant.PermissionCodes;
import io.kbrag.domain.entity.Role;
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

/**
 * Role administration endpoints: permission grants and knowledge base data scope.
 *
 * <p>The listing is also readable with {@code user:manage}, because assigning a role means picking one from a
 * list; being able to grant a role without being able to see the roles is not a workable screen.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/roles")
@RequiredArgsConstructor
@RequiresPermission(PermissionCodes.ROLE_MANAGE)
public class RoleController {

    private final RoleService roleService;
    private final RoleAppScopeService appScopeService;

    /**
     * Lists every role with its grants and its scope, built in ones first.
     *
     * <p>Besides the two administration screens, doc:review is admitted too (M16): granting a
     * restricted document to roles means picking them from this list, and a reviewer who cannot
     * see the catalogue cannot grant anything.
     *
     * @return roles
     */
    @GetMapping
    @RequiresPermission({PermissionCodes.ROLE_MANAGE, PermissionCodes.USER_MANAGE,
            PermissionCodes.DOC_REVIEW})
    public Result<List<RoleResponse>> list() {
        List<Role> roles = roleService.list();
        var appScopes = appScopeService.scopesOf(roles.stream().map(Role::getRoleId).toList());
        return Result.success(roles.stream()
                .map(role -> RoleResponse.from(role,
                        roleService.permissionCodesOf(role.getRoleId()),
                        roleService.kbScopeOf(role.getRoleId()), appScopes.get(role.getRoleId())))
                .toList());
    }

    /**
     * The permission catalogue the role editor renders, grouped by module.
     *
     * @return catalogue entries in display order
     */
    @GetMapping("/permissions")
    public Result<List<PermissionResponse>> permissions() {
        return Result.success(roleService.permissionCatalogue().stream()
                .map(PermissionResponse::from)
                .toList());
    }

    /** 只列出已授权角色所属租户的应用；新角色使用当前用户租户。 */
    @GetMapping("/app-options")
    public Result<List<RoleAppOptionResponse>> appOptions(
            @RequestParam(value = "role_id", required = false) String roleId) {
        String tenantId = roleId == null ? AccessGuard.currentUser().tenantId()
                : roleService.get(roleId).getTenantId();
        return Result.success(appScopeService.optionsInTenant(tenantId).stream()
                .map(app -> new RoleAppOptionResponse(app.getAppId(), app.getName())).toList());
    }

    /**
     * Loads one role.
     *
     * @param roleId role business id
     * @return role view
     */
    @GetMapping("/{roleId}")
    public Result<RoleResponse> get(@PathVariable String roleId) {
        Role role = roleService.get(roleId);
        return Result.success(responseOf(role));
    }

    /**
     * Creates a role together with its grants and its data scope.
     *
     * @param request role definition
     * @return created role
     */
    @PostMapping
    @AuditedOperation(module = "ROLE", action = "CREATE", targetType = "ROLE",
            targetId = "#result.data.roleId")
    public Result<RoleResponse> create(@Valid @RequestBody SaveRoleRequest request) {
        Role role = roleService.create(request.code(), request.name(), request.description(),
                request.kbScopeAll(), request.kbIds(), request.permissionCodes(),
                request.appScopeAll(), request.appIds());
        return Result.success(responseOf(role));
    }

    /**
     * Replaces the definition, the grants and the data scope of a role.
     *
     * @param roleId  role business id
     * @param request role definition, its code is ignored
     * @return updated role
     */
    @PutMapping("/{roleId}")
    @AuditedOperation(module = "ROLE", action = "UPDATE", targetType = "ROLE", targetId = "#roleId")
    public Result<RoleResponse> update(@PathVariable String roleId,
                                      @Valid @RequestBody SaveRoleRequest request) {
        roleService.update(roleId, request.name(), request.description(),
                request.kbScopeAll(), request.kbIds(), request.permissionCodes(),
                request.appScopeAll(), request.appIds());
        Role role = roleService.get(roleId);
        return Result.success(responseOf(role));
    }

    /**
     * Deletes a role that nobody holds any more.
     *
     * @param roleId role business id
     * @return empty success envelope
     */
    @DeleteMapping("/{roleId}")
    @AuditedOperation(module = "ROLE", action = "DELETE", targetType = "ROLE", targetId = "#roleId")
    public Result<Void> delete(@PathVariable String roleId) {
        roleService.delete(roleId);
        return Result.success(null);
    }

    private RoleResponse responseOf(Role role) {
        String roleId = role.getRoleId();
        return RoleResponse.from(role, roleService.permissionCodesOf(roleId),
                roleService.kbScopeOf(roleId), appScopeService.scopesOf(List.of(roleId)).get(roleId));
    }
}
