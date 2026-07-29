package io.kbrag.app.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.entity.Permission;
import io.kbrag.domain.entity.Role;
import io.kbrag.domain.entity.RoleKbScope;
import io.kbrag.domain.entity.RolePermission;
import io.kbrag.domain.entity.UserRole;
import io.kbrag.domain.mapper.KnowledgeBaseMapper;
import io.kbrag.domain.mapper.PermissionMapper;
import io.kbrag.domain.mapper.RoleKbScopeMapper;
import io.kbrag.domain.mapper.RoleMapper;
import io.kbrag.domain.mapper.RolePermissionMapper;
import io.kbrag.domain.mapper.UserRoleMapper;
import io.kbrag.domain.entity.KnowledgeBase;
import io.kbrag.domain.service.BizIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Administration of roles: their permission grants and their knowledge base data scope.
 *
 * <p>Every write here clears the whole permission cache. A role edit is rare and affects an unknown set of
 * people, so recomputing on next use is the cheap side of the trade; the expensive side would be a revoked
 * grant that keeps working until each holder's session happens to end.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleService {

    private final RoleMapper roleMapper;
    private final PermissionMapper permissionMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final RoleKbScopeMapper roleKbScopeMapper;
    private final UserRoleMapper userRoleMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final BizIdGenerator idGenerator;
    private final PrincipalResolver principalResolver;

    /**
     * Lists every role, built in ones first.
     *
     * @return roles ordered for the console list
     */
    public List<Role> list() {
        return roleMapper.selectList(new LambdaQueryWrapper<Role>()
                .orderByDesc(Role::getBuiltin)
                .orderByAsc(Role::getId));
    }

    /**
     * Loads one role.
     *
     * @param roleId role business id
     * @return role record
     */
    public Role get(String roleId) {
        return requireRole(roleId);
    }

    /**
     * The permission catalogue, grouped in display order by the console.
     *
     * @return permissions ordered by module then position
     */
    public List<Permission> permissionCatalogue() {
        return permissionMapper.selectList(new LambdaQueryWrapper<Permission>()
                .orderByAsc(Permission::getModule)
                .orderByAsc(Permission::getSortOrder));
    }

    /**
     * Permission codes granted to one role.
     *
     * @param roleId role business id
     * @return granted permission codes
     */
    public List<String> permissionCodesOf(String roleId) {
        return rolePermissionMapper.selectList(new LambdaQueryWrapper<RolePermission>()
                        .eq(RolePermission::getRoleId, roleId))
                .stream()
                .map(RolePermission::getPermissionCode)
                .toList();
    }

    /**
     * Knowledge bases inside the data scope of one role; empty when the role sees every base.
     *
     * @param roleId role business id
     * @return scoped knowledge base ids
     */
    public List<String> kbScopeOf(String roleId) {
        return roleKbScopeMapper.selectList(new LambdaQueryWrapper<RoleKbScope>()
                        .eq(RoleKbScope::getRoleId, roleId))
                .stream()
                .map(RoleKbScope::getKbId)
                .toList();
    }

    /**
     * Creates a role together with its grants and its data scope.
     *
     * @param code            role code, unique
     * @param name            display label
     * @param description     purpose note
     * @param kbScopeAll      whether the role sees every knowledge base
     * @param kbIds           scoped knowledge bases, ignored when {@code kbScopeAll}
     * @param permissionCodes granted permission codes
     * @return created role
     */
    @Transactional(rollbackFor = Exception.class)
    public Role create(String code, String name, String description, boolean kbScopeAll,
                       List<String> kbIds, List<String> permissionCodes) {
        String roleCode = normalizeCode(code);
        if (findByCode(roleCode) != null) {
            throw BizException.invalidParam("role code already taken: " + roleCode);
        }
        Role role = new Role();
        role.setRoleId(idGenerator.roleId());
        role.setCode(roleCode);
        role.setName(name);
        role.setDescription(description);
        role.setBuiltin(0);
        role.setKbScopeAll(kbScopeAll ? 1 : 0);
        roleMapper.insert(role);

        replacePermissions(role.getRoleId(), permissionCodes);
        replaceKbScope(role.getRoleId(), kbScopeAll, kbIds);
        principalResolver.evictAll();
        log.info("role created, roleId={}, code={}", role.getRoleId(), roleCode);
        return role;
    }

    /**
     * Replaces the definition, the grants and the data scope of a role.
     *
     * <p>The role code is not editable, on a built in role or any other. It is what configuration and the
     * provisioning default refer to, so renaming it would break a reference nothing in this screen shows.
     *
     * @param roleId          role business id
     * @param name            display label
     * @param description     purpose note
     * @param kbScopeAll      whether the role sees every knowledge base
     * @param kbIds           scoped knowledge bases, ignored when {@code kbScopeAll}
     * @param permissionCodes complete new set of granted permission codes
     */
    @Transactional(rollbackFor = Exception.class)
    public void update(String roleId, String name, String description, boolean kbScopeAll,
                       List<String> kbIds, List<String> permissionCodes) {
        Role role = requireRole(roleId);
        if (name != null && !name.isBlank()) {
            role.setName(name);
        }
        role.setDescription(description);
        role.setKbScopeAll(kbScopeAll ? 1 : 0);
        roleMapper.updateById(role);

        replacePermissions(roleId, permissionCodes);
        replaceKbScope(roleId, kbScopeAll, kbIds);
        principalResolver.evictAll();
        log.info("role updated, roleId={}", roleId);
    }

    /**
     * Deletes a role, refusing to leave holders behind.
     *
     * <p>A role still held is not deleted, and the count is reported instead of the bindings being cleaned
     * up silently. Removing the last role of someone is how an account turns into one that can log in and
     * see nothing, and the operator is the one who should decide that, not a cascade.
     *
     * @param roleId role business id
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(String roleId) {
        Role role = requireRole(roleId);
        if (role.builtin()) {
            throw BizException.invalidParam("built in role cannot be deleted: " + role.getCode());
        }
        long holders = userRoleMapper.selectCount(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getRoleId, roleId));
        if (holders > 0) {
            throw BizException.invalidParam(
                    "role is still held by " + holders + " user(s), revoke it first");
        }
        rolePermissionMapper.deleteByRoleId(roleId);
        roleKbScopeMapper.deleteByRoleId(roleId);
        roleMapper.deleteById(role.getId());
        principalResolver.evictAll();
        log.info("role deleted, roleId={}", roleId);
    }

    /**
     * Drops a deleted knowledge base from every role scope.
     *
     * <p>Called by the knowledge base deletion so the role editor stops offering a base that no longer
     * exists.
     *
     * @param kbId knowledge base business id
     */
    @Transactional(rollbackFor = Exception.class)
    public void detachKnowledgeBase(String kbId) {
        int removed = roleKbScopeMapper.deleteByKbId(kbId);
        if (removed > 0) {
            principalResolver.evictAll();
            log.info("knowledge base detached from role scopes, kbId={}, rows={}", kbId, removed);
        }
    }

    private void replacePermissions(String roleId, List<String> permissionCodes) {
        rolePermissionMapper.deleteByRoleId(roleId);
        if (permissionCodes == null || permissionCodes.isEmpty()) {
            return;
        }
        Set<String> distinct = new LinkedHashSet<>(permissionCodes);
        Set<String> known = permissionMapper.selectList(new LambdaQueryWrapper<Permission>()
                        .in(Permission::getCode, distinct))
                .stream()
                .map(Permission::getCode)
                .collect(Collectors.toSet());
        if (known.size() != distinct.size()) {
            // A code no endpoint declares would render as a checkbox that grants nothing.
            throw BizException.invalidParam("unknown permission code in the submitted set");
        }
        for (String code : distinct) {
            RolePermission grant = new RolePermission();
            grant.setRoleId(roleId);
            grant.setPermissionCode(code);
            rolePermissionMapper.insert(grant);
        }
    }

    private void replaceKbScope(String roleId, boolean kbScopeAll, List<String> kbIds) {
        roleKbScopeMapper.deleteByRoleId(roleId);
        if (kbScopeAll || kbIds == null || kbIds.isEmpty()) {
            return;
        }
        Set<String> distinct = new LinkedHashSet<>(kbIds);
        long known = knowledgeBaseMapper.selectCount(new LambdaQueryWrapper<KnowledgeBase>()
                .in(KnowledgeBase::getKbId, distinct));
        if (known != distinct.size()) {
            throw BizException.invalidParam("unknown knowledge base in the submitted scope");
        }
        for (String kbId : distinct) {
            RoleKbScope scope = new RoleKbScope();
            scope.setRoleId(roleId);
            scope.setKbId(kbId);
            roleKbScopeMapper.insert(scope);
        }
    }

    private String normalizeCode(String code) {
        if (code == null || code.isBlank()) {
            throw BizException.invalidParam("role code is required");
        }
        return code.trim().toUpperCase();
    }

    private Role findByCode(String code) {
        return roleMapper.selectOne(new LambdaQueryWrapper<Role>()
                .eq(Role::getCode, code)
                .last("limit 1"));
    }

    private Role requireRole(String roleId) {
        Role role = roleMapper.selectOne(new LambdaQueryWrapper<Role>()
                .eq(Role::getRoleId, roleId)
                .last("limit 1"));
        if (role == null) {
            throw BizException.notFound("role not found: " + roleId);
        }
        return role;
    }
}
