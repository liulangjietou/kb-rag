package io.kbrag.app.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.entity.AdminUser;
import io.kbrag.domain.entity.Role;
import io.kbrag.domain.entity.RoleKbScope;
import io.kbrag.domain.entity.RolePermission;
import io.kbrag.domain.entity.Tenant;
import io.kbrag.domain.entity.UserRole;
import io.kbrag.domain.mapper.AdminUserMapper;
import io.kbrag.domain.mapper.RoleKbScopeMapper;
import io.kbrag.domain.mapper.RoleMapper;
import io.kbrag.domain.mapper.RolePermissionMapper;
import io.kbrag.domain.mapper.TenantMapper;
import io.kbrag.domain.mapper.UserRoleMapper;
import io.kbrag.domain.model.UserPrincipal;
import io.kbrag.domain.port.PrincipalCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Resolves the flattened permissions of a user, with a write through cache.
 *
 * <p>Joining roles, grants and knowledge base scopes takes four queries. Paying them on every single
 * console call would tax every screen for a set of rows that changes a handful of times a month, so the
 * result is cached per user name and invalidated explicitly whenever a grant moves.
 *
 * <p><b>缓存放哪里由部署形态决定，这个类不关心。</b> 它对着 {@link PrincipalCache} 端口编程：
 * {@code cache.provider=local} 时是进程内的 Map，单节点下最优；{@code =redis} 时是各节点共享的一份，
 * 多节点下 A 节点改掉一个角色，B 节点的下一次请求立刻看到。会话与权限由此在同一个开关下同进同退——
 * 只切一半会得到"登录态共享、授权判据不共享"这种更难察觉的错位。
 *
 * <p>Invalidation is deliberately blunt: editing a role clears the whole cache instead of working out who
 * held it. Getting that set right needs another query, and a stale grant is a security bug while a cleared
 * cache is only four queries.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PrincipalResolver {

    private final AdminUserMapper adminUserMapper;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final RoleKbScopeMapper roleKbScopeMapper;
    private final TenantMapper tenantMapper;

    private final PrincipalCache cache;

    /**
     * Resolves the caller behind an authenticated session.
     *
     * @param username login name carried by the session token
     * @return flattened permissions of that user
     * @throws BizException unauthorized when the account vanished or was suspended mid session
     */
    public UserPrincipal resolve(String username) {
        UserPrincipal cached = cache.get(username);
        if (cached != null) {
            return cached;
        }
        UserPrincipal principal = load(username);
        cache.put(username, principal);
        return principal;
    }

    /**
     * Drops the cached permissions of one user, after its roles changed.
     *
     * @param username login name
     */
    public void evict(String username) {
        cache.evict(username);
    }

    /** Drops every cached entry, after a role definition or a knowledge base scope changed. */
    public void evictAll() {
        cache.evictAll();
    }

    private UserPrincipal load(String username) {
        AdminUser user = adminUserMapper.selectOne(new LambdaQueryWrapper<AdminUser>()
                .eq(AdminUser::getUsername, username)
                .last("limit 1"));
        if (user == null) {
            throw BizException.unauthorized("session no longer valid");
        }
        // An operator who suspends an account expects it to stop working now, not when its token expires.
        if (!user.enabled()) {
            throw BizException.unauthorized("account disabled");
        }
        // Same argument one level up: disabling a tenant must cut every session of the tenant on its
        // next request, not when the individual tokens expire.
        Tenant tenant = tenantMapper.selectOne(new LambdaQueryWrapper<Tenant>()
                .eq(Tenant::getTenantId, user.getTenantId())
                .last("limit 1"));
        if (tenant != null && !tenant.enabled()) {
            throw BizException.unauthorized("tenant disabled");
        }

        List<UserRole> bindings = userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getUserId, user.getUserId()));
        Set<String> roleIds = bindings.stream()
                .map(UserRole::getRoleId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (roleIds.isEmpty()) {
            // Authenticated and granted nothing. The console renders an empty navigation rather than an
            // error, because "ask an administrator for a role" is the accurate message here.
            return new UserPrincipal(user.getUserId(), user.getTenantId(), user.getUsername(),
                    displayName(user), user.getSource(), Set.of(), Set.of(), Set.of(), false, Set.of());
        }

        List<Role> roles = roleMapper.selectList(new LambdaQueryWrapper<Role>()
                .in(Role::getRoleId, roleIds));
        // Roles a delete removed are simply absent here; reading against the surviving ones keeps a
        // half finished cleanup from denying a login outright.
        Set<String> liveRoleIds = roles.stream()
                .map(Role::getRoleId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> roleCodes = roles.stream()
                .map(Role::getCode)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        boolean kbScopeAll = roles.stream().anyMatch(Role::kbScopeAll);

        Set<String> permissions = liveRoleIds.isEmpty() ? Set.of()
                : rolePermissionMapper.selectList(new LambdaQueryWrapper<RolePermission>()
                        .in(RolePermission::getRoleId, liveRoleIds))
                .stream()
                .map(RolePermission::getPermissionCode)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<String> kbIds = Set.of();
        if (!kbScopeAll && !liveRoleIds.isEmpty()) {
            kbIds = roleKbScopeMapper.selectList(new LambdaQueryWrapper<RoleKbScope>()
                            .in(RoleKbScope::getRoleId, liveRoleIds))
                    .stream()
                    .map(RoleKbScope::getKbId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        return new UserPrincipal(user.getUserId(), user.getTenantId(), user.getUsername(),
                displayName(user), user.getSource(), roleCodes, liveRoleIds, permissions, kbScopeAll, kbIds);
    }

    private String displayName(AdminUser user) {
        String name = user.getDisplayName();
        return name == null || name.isBlank() ? user.getUsername() : name;
    }
}
