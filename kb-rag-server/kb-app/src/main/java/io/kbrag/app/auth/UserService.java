package io.kbrag.app.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.constant.BuiltinRoles;
import io.kbrag.domain.entity.AdminUser;
import io.kbrag.domain.entity.Role;
import io.kbrag.domain.entity.UserRole;
import io.kbrag.domain.enums.UserSource;
import io.kbrag.domain.enums.UserStatus;
import io.kbrag.domain.mapper.AdminUserMapper;
import io.kbrag.domain.mapper.RoleMapper;
import io.kbrag.domain.mapper.UserRoleMapper;
import io.kbrag.domain.model.UserPrincipal;
import io.kbrag.domain.service.BizIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Administration of console users and of the roles they hold.
 *
 * <p>There is no self service registration. Accounts appear in exactly two ways, and both are traceable:
 * an operator holding {@code user:manage} creates one, or a corporate directory login provisions one on
 * first use. An open registration form on a knowledge base console would let anybody outside reach the
 * point where a role is all that stands between them and the content.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private static final int MUST_CHANGE = 1;
    private static final int NOT_REQUIRED = 0;

    /** Shortest local password accepted; directory accounts are not bound by it. */
    private static final int MIN_PASSWORD_LENGTH = 8;

    private final AdminUserMapper adminUserMapper;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final BizIdGenerator idGenerator;
    private final BCryptPasswordEncoder passwordEncoder;
    private final TokenStore tokenStore;
    private final PrincipalResolver principalResolver;
    private final KbProperties properties;

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
    public IPage<AdminUser> list(String keyword, UserStatus status, UserSource source,
                                 long page, long size) {
        LambdaQueryWrapper<AdminUser> wrapper = new LambdaQueryWrapper<AdminUser>()
                .orderByDesc(AdminUser::getId);
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.like(AdminUser::getUsername, keyword)
                    .or().like(AdminUser::getDisplayName, keyword));
        }
        if (status != null) {
            wrapper.eq(AdminUser::getStatus, status);
        }
        if (source != null) {
            wrapper.eq(AdminUser::getSource, source);
        }
        return adminUserMapper.selectPage(new Page<>(page, size), wrapper);
    }

    /**
     * Loads one account.
     *
     * @param userId user business id
     * @return account record
     */
    public AdminUser get(String userId) {
        return requireUser(userId);
    }

    /**
     * Creates a local account with an initial password that must be rotated at first login.
     *
     * @param username    login name, unique across local and directory accounts
     * @param displayName display label, falls back to the login name
     * @param email       contact address, optional
     * @param password    initial password chosen by the operator
     * @param roleIds     roles granted right away, may be empty
     * @return created account
     */
    @Transactional(rollbackFor = Exception.class)
    public AdminUser create(String username, String displayName, String email,
                            String password, List<String> roleIds) {
        String login = normalizeUsername(username);
        if (findByUsername(login) != null) {
            throw BizException.invalidParam("username already taken: " + login);
        }
        requirePasswordStrength(password);

        AdminUser user = new AdminUser();
        user.setUserId(idGenerator.userId());
        user.setUsername(login);
        user.setDisplayName(displayName == null || displayName.isBlank() ? login : displayName);
        user.setEmail(email);
        user.setSource(UserSource.LOCAL);
        user.setStatus(UserStatus.ENABLED);
        user.setPasswordHash(passwordEncoder.encode(password));
        // The operator knows this password, so the account is not the user's own until they rotate it.
        user.setMustChangePassword(MUST_CHANGE);
        adminUserMapper.insert(user);

        replaceRoles(user.getUserId(), roleIds);
        log.info("user created, userId={}, username={}, roles={}", user.getUserId(), login,
                roleIds == null ? 0 : roleIds.size());
        return user;
    }

    /**
     * Provisions an account for a directory login that has no local record yet.
     *
     * <p>Called only after the directory has already accepted the credentials, so the person is known to
     * exist and to have authenticated. The alternative - refusing the login until an operator pre-creates
     * the row - means every new colleague files a ticket to see a read only page.
     *
     * <p>The granted role comes from configuration and defaults to read only. That is the whole reason
     * automatic provisioning is safe: it hands out visibility, never the ability to change anything.
     *
     * @param username directory login name, without the domain suffix
     * @return provisioned account
     */
    @Transactional(rollbackFor = Exception.class)
    public AdminUser provisionDirectoryUser(String username) {
        String login = normalizeUsername(username);
        AdminUser user = new AdminUser();
        user.setUserId(idGenerator.userId());
        user.setUsername(login);
        user.setDisplayName(login);
        user.setSource(UserSource.LDAP);
        user.setStatus(UserStatus.ENABLED);
        // No local password exists, and none is invented: a hash here would be a second credential able
        // to outlive the directory account it was created for.
        user.setPasswordHash(null);
        user.setMustChangePassword(NOT_REQUIRED);
        adminUserMapper.insert(user);

        String defaultRoleCode = properties.getAuth().getLdap().getDefaultRoleCode();
        Role role = findRoleByCode(defaultRoleCode);
        if (role == null) {
            // A misconfigured role name must not fail the login: the account is created without a role and
            // lands on an empty console, which an operator can fix by granting one.
            log.error("default single sign on role not found, roleCode={}, userId={}",
                    defaultRoleCode, user.getUserId());
        } else {
            bindRole(user.getUserId(), role.getRoleId());
        }
        log.info("directory user provisioned, userId={}, username={}, roleCode={}",
                user.getUserId(), login, role == null ? null : role.getCode());
        return user;
    }

    /**
     * Updates the display fields of an account.
     *
     * <p>The login name is not editable. It is the key session tokens and every audit row are written
     * against, so renaming it would orphan the account's own history.
     *
     * @param userId      user business id
     * @param displayName new display label
     * @param email       new contact address
     */
    @Transactional(rollbackFor = Exception.class)
    public void update(String userId, String displayName, String email) {
        AdminUser user = requireUser(userId);
        if (displayName != null && !displayName.isBlank()) {
            user.setDisplayName(displayName);
        }
        user.setEmail(email);
        adminUserMapper.updateById(user);
        principalResolver.evict(user.getUsername());
        log.info("user updated, userId={}", userId);
    }

    /**
     * Enables or suspends an account, ending its sessions when suspended.
     *
     * @param userId user business id
     * @param status new lifecycle state
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(String userId, UserStatus status) {
        AdminUser user = requireUser(userId);
        requireNotSelf(user, "you cannot disable your own account");
        user.setStatus(status);
        adminUserMapper.updateById(user);
        principalResolver.evict(user.getUsername());
        if (status == UserStatus.DISABLED) {
            // Suspension has to take effect now. Leaving the sessions alive would keep the account working
            // for up to a full token lifetime after an operator locked it out.
            tokenStore.revokeAll(user.getUsername());
        }
        log.info("user status changed, userId={}, status={}", userId, status);
    }

    /**
     * Resets the password of a local account and ends its sessions.
     *
     * @param userId      user business id
     * @param newPassword replacement password
     */
    @Transactional(rollbackFor = Exception.class)
    public void resetPassword(String userId, String newPassword) {
        AdminUser user = requireUser(userId);
        if (user.directoryAccount()) {
            throw BizException.invalidParam(
                    "password of a single sign on account is managed by the directory");
        }
        requirePasswordStrength(newPassword);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(MUST_CHANGE);
        adminUserMapper.updateById(user);
        tokenStore.revokeAll(user.getUsername());
        log.info("user password reset, userId={}", userId);
    }

    /**
     * Replaces the roles held by an account.
     *
     * @param userId  user business id
     * @param roleIds complete new set of role business ids
     */
    @Transactional(rollbackFor = Exception.class)
    public void assignRoles(String userId, List<String> roleIds) {
        AdminUser user = requireUser(userId);
        replaceRoles(userId, roleIds);
        principalResolver.evict(user.getUsername());
        log.info("user roles replaced, userId={}, roles={}", userId, roleIds == null ? 0 : roleIds.size());
    }

    /**
     * Deletes an account and its role bindings.
     *
     * @param userId user business id
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(String userId) {
        AdminUser user = requireUser(userId);
        requireNotSelf(user, "you cannot delete your own account");
        userRoleMapper.deleteByUserId(userId);
        adminUserMapper.deleteById(user.getId());
        tokenStore.revokeAll(user.getUsername());
        principalResolver.evict(user.getUsername());
        log.info("user deleted, userId={}", userId);
    }

    /**
     * Role business ids held by one account.
     *
     * @param userId user business id
     * @return role business ids
     */
    public List<String> roleIdsOf(String userId) {
        return userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                        .eq(UserRole::getUserId, userId))
                .stream()
                .map(UserRole::getRoleId)
                .toList();
    }

    /**
     * Role names held by several accounts, for rendering one user list without a query per row.
     *
     * @param userIds user business ids
     * @return role display names per user business id
     */
    public Map<String, List<String>> roleNamesOf(Collection<String> userIds) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (userIds == null || userIds.isEmpty()) {
            return result;
        }
        List<UserRole> bindings = userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                .in(UserRole::getUserId, userIds));
        if (bindings.isEmpty()) {
            return result;
        }
        Set<String> roleIds = bindings.stream()
                .map(UserRole::getRoleId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, String> names = roleMapper.selectList(new LambdaQueryWrapper<Role>()
                        .in(Role::getRoleId, roleIds))
                .stream()
                .collect(Collectors.toMap(Role::getRoleId, Role::getName, (a, b) -> a));
        for (UserRole binding : bindings) {
            String name = names.get(binding.getRoleId());
            if (name != null) {
                result.computeIfAbsent(binding.getUserId(), k -> new ArrayList<>()).add(name);
            }
        }
        return result;
    }

    /**
     * Grants the super administrator role, used by the bootstrap of an empty database.
     *
     * @param userId user business id
     */
    @Transactional(rollbackFor = Exception.class)
    public void grantBootstrapRole(String userId) {
        Role role = findRoleByCode(BuiltinRoles.SUPER_ADMIN);
        if (role == null) {
            log.error("bootstrap role missing, roleCode={}", BuiltinRoles.SUPER_ADMIN);
            return;
        }
        bindRole(userId, role.getRoleId());
        log.info("bootstrap role granted, userId={}", userId);
    }

    private void replaceRoles(String userId, List<String> roleIds) {
        userRoleMapper.deleteByUserId(userId);
        if (roleIds == null || roleIds.isEmpty()) {
            return;
        }
        Set<String> distinct = new LinkedHashSet<>(roleIds);
        long known = roleMapper.selectCount(new LambdaQueryWrapper<Role>().in(Role::getRoleId, distinct));
        if (known != distinct.size()) {
            throw BizException.invalidParam("unknown role in the submitted set");
        }
        for (String roleId : distinct) {
            bindRole(userId, roleId);
        }
    }

    private void bindRole(String userId, String roleId) {
        UserRole binding = new UserRole();
        binding.setUserId(userId);
        binding.setRoleId(roleId);
        userRoleMapper.insert(binding);
    }

    private void requirePasswordStrength(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw BizException.invalidParam(
                    "password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
    }

    /**
     * Refuses an operation an operator would aim at their own account.
     *
     * <p>Suspending or deleting yourself is the one mistake in this screen that cannot be undone from the
     * screen: after it there may be nobody left holding {@code user:manage}, and the fix is a manual
     * database edit.
     */
    private void requireNotSelf(AdminUser user, String message) {
        UserPrincipal caller = AccessGuard.currentUserOrNull();
        if (caller != null && caller.userId() != null && caller.userId().equals(user.getUserId())) {
            throw BizException.invalidParam(message);
        }
    }

    /**
     * Normalises a login name to lower case.
     *
     * <p>Directories treat {@code Zhang} and {@code zhang} as one account and will bind either spelling.
     * Without folding the case here, the same colleague typing their name differently on a second visit
     * would be provisioned a second account with its own roles.
     */
    private String normalizeUsername(String username) {
        if (username == null || username.isBlank()) {
            throw BizException.invalidParam("username is required");
        }
        return username.trim().toLowerCase();
    }

    private AdminUser findByUsername(String username) {
        return adminUserMapper.selectOne(new LambdaQueryWrapper<AdminUser>()
                .eq(AdminUser::getUsername, username)
                .last("limit 1"));
    }

    private Role findRoleByCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return roleMapper.selectOne(new LambdaQueryWrapper<Role>()
                .eq(Role::getCode, code)
                .last("limit 1"));
    }

    private AdminUser requireUser(String userId) {
        AdminUser user = adminUserMapper.selectOne(new LambdaQueryWrapper<AdminUser>()
                .eq(AdminUser::getUserId, userId)
                .last("limit 1"));
        if (user == null) {
            throw BizException.notFound("user not found: " + userId);
        }
        return user;
    }
}
