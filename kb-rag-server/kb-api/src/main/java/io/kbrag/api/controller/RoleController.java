package io.kbrag.api.controller;

import io.kbrag.api.annotation.RequiresPermission;
import io.kbrag.api.dto.PermissionResponse;
import io.kbrag.api.dto.RoleResponse;
import io.kbrag.api.dto.SaveRoleRequest;
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

    /**
     * Lists every role with its grants and its scope, built in ones first.
     *
     * @return roles
     */
    @GetMapping
    @RequiresPermission({PermissionCodes.ROLE_MANAGE, PermissionCodes.USER_MANAGE})
    public Result<List<RoleResponse>> list() {
        return Result.success(roleService.list().stream()
                .map(role -> RoleResponse.from(role,
                        roleService.permissionCodesOf(role.getRoleId()),
                        roleService.kbScopeOf(role.getRoleId())))
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

    /**
     * Loads one role.
     *
     * @param roleId role business id
     * @return role view
     */
    @GetMapping("/{roleId}")
    public Result<RoleResponse> get(@PathVariable String roleId) {
        Role role = roleService.get(roleId);
        return Result.success(RoleResponse.from(role,
                roleService.permissionCodesOf(roleId),
                roleService.kbScopeOf(roleId)));
    }

    /**
     * Creates a role together with its grants and its data scope.
     *
     * @param request role definition
     * @return created role
     */
    @PostMapping
    public Result<RoleResponse> create(@Valid @RequestBody SaveRoleRequest request) {
        Role role = roleService.create(request.code(), request.name(), request.description(),
                request.kbScopeAll(), request.kbIds(), request.permissionCodes());
        return Result.success(RoleResponse.from(role,
                roleService.permissionCodesOf(role.getRoleId()),
                roleService.kbScopeOf(role.getRoleId())));
    }

    /**
     * Replaces the definition, the grants and the data scope of a role.
     *
     * @param roleId  role business id
     * @param request role definition, its code is ignored
     * @return updated role
     */
    @PutMapping("/{roleId}")
    public Result<RoleResponse> update(@PathVariable String roleId,
                                      @Valid @RequestBody SaveRoleRequest request) {
        roleService.update(roleId, request.name(), request.description(),
                request.kbScopeAll(), request.kbIds(), request.permissionCodes());
        Role role = roleService.get(roleId);
        return Result.success(RoleResponse.from(role,
                roleService.permissionCodesOf(roleId),
                roleService.kbScopeOf(roleId)));
    }

    /**
     * Deletes a role that nobody holds any more.
     *
     * @param roleId role business id
     * @return empty success envelope
     */
    @DeleteMapping("/{roleId}")
    public Result<Void> delete(@PathVariable String roleId) {
        roleService.delete(roleId);
        return Result.success(null);
    }
}
