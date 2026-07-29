package io.kbrag.app.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.AdminUser;
import io.kbrag.domain.entity.LoginAudit;
import io.kbrag.domain.enums.DirectoryBindResult;
import io.kbrag.domain.enums.LoginMode;
import io.kbrag.domain.enums.LoginResult;
import io.kbrag.domain.mapper.AdminUserMapper;
import io.kbrag.domain.mapper.LoginAuditMapper;
import io.kbrag.domain.port.DirectoryAuthenticator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Console authentication: credential verification, brute force protection and audit.
 *
 * <p>Every attempt, successful or not, lands in {@code t_kb_login_audit}; the lock decision is then
 * derived from that same table instead of an in memory counter, so a restart cannot be used to
 * reset the failure count.
 *
 * <p>Two entry points share this service. The local one compares a BCrypt hash; the single sign on one
 * delegates to the corporate directory and provisions an account on first use. Which one a given account
 * may use is fixed by its {@code source} and enforced in both directions - see {@link LoginMode}.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final int SUCCESS_FLAG = 1;
    private static final int FAILURE_FLAG = 0;
    private static final int NOT_REQUIRED = 0;

    private final AdminUserMapper adminUserMapper;
    private final LoginAuditMapper loginAuditMapper;
    private final TokenStore tokenStore;
    private final KbProperties properties;
    private final BCryptPasswordEncoder passwordEncoder;
    private final DirectoryAuthenticator directoryAuthenticator;
    private final UserService userService;
    private final PrincipalResolver principalResolver;

    /**
     * Verifies credentials through the requested entry point and issues a session token.
     *
     * @param username submitted user name
     * @param password submitted password
     * @param mode     entry point the attempt came through
     * @param ip       source address of the attempt
     * @return session ticket
     */
    @Transactional(rollbackFor = Exception.class)
    public LoginTicket login(String username, String password, LoginMode mode, String ip) {
        String login = normalize(username);
        if (isLocked(login, ip)) {
            audit(login, ip, false, LoginResult.ACCOUNT_LOCKED);
            log.info("login rejected by lock window, username={}, ip={}", login, ip);
            throw BizException.unauthorized("account temporarily locked, retry after "
                    + properties.getAuth().getLockMinutes() + " minutes");
        }
        LoginTicket ticket = mode == LoginMode.SSO
                ? loginWithDirectory(login, password, ip)
                : loginWithLocalPassword(login, password, ip);
        log.info("login succeeded, username={}, mode={}, ip={}", login, mode, ip);
        return ticket;
    }

    /**
     * Whether the console should offer the single sign on tab.
     *
     * @return {@code true} when the directory integration is configured
     */
    public boolean singleSignOnAvailable() {
        return directoryAuthenticator.available();
    }

    private LoginTicket loginWithLocalPassword(String username, String password, String ip) {
        AdminUser user = findByUsername(username);
        if (user == null) {
            audit(username, ip, false, LoginResult.USER_NOT_FOUND);
            throw BizException.unauthorized("invalid username or password");
        }
        if (user.directoryAccount()) {
            audit(username, ip, false, LoginResult.WRONG_LOGIN_MODE);
            throw BizException.unauthorized("this account signs in through single sign on");
        }
        if (user.getPasswordHash() == null
                || !passwordEncoder.matches(password, user.getPasswordHash())) {
            audit(username, ip, false, LoginResult.BAD_PASSWORD);
            throw BizException.unauthorized("invalid username or password");
        }
        requireEnabled(user, ip);
        return issue(user, username, ip);
    }

    private LoginTicket loginWithDirectory(String username, String password, String ip) {
        if (!directoryAuthenticator.available()) {
            audit(username, ip, false, LoginResult.SSO_DISABLED);
            throw BizException.unauthorized("single sign on is not enabled");
        }
        AdminUser existing = findByUsername(username);
        // Checked before the bind: a local account must never be authenticated by the directory, or a
        // domain account whose name matches the local administrator would inherit it.
        if (existing != null && !existing.directoryAccount()) {
            audit(username, ip, false, LoginResult.WRONG_LOGIN_MODE);
            throw BizException.unauthorized("this account signs in with a local password");
        }
        // Also checked before the bind, so a suspended account cannot be used to probe the directory.
        if (existing != null) {
            requireEnabled(existing, ip);
        }

        DirectoryBindResult bind = directoryAuthenticator.bind(username, password);
        if (bind == DirectoryBindResult.SERVICE_UNAVAILABLE) {
            // Audited as a failure but never counted towards the lockout, which is why the reason is a
            // separate value: one domain controller outage would otherwise lock out everyone who retried.
            audit(username, ip, false, LoginResult.DIRECTORY_UNAVAILABLE);
            throw BizException.unauthorized("directory is unavailable, try again later");
        }
        if (bind == DirectoryBindResult.INVALID_CREDENTIALS) {
            audit(username, ip, false, LoginResult.DIRECTORY_REJECTED);
            throw BizException.unauthorized("invalid username or password");
        }

        AdminUser user = existing == null ? userService.provisionDirectoryUser(username) : existing;
        return issue(user, username, ip);
    }

    private LoginTicket issue(AdminUser user, String username, String ip) {
        audit(username, ip, true, LoginResult.SUCCESS);
        user.setLastLoginAt(LocalDateTime.now());
        adminUserMapper.updateById(user);
        // A freshly provisioned account has no cached permissions, and a returning one may have been
        // regranted while it was away.
        principalResolver.evict(username);
        return new LoginTicket(tokenStore.issue(username), user.mustChangePassword());
    }

    private void requireEnabled(AdminUser user, String ip) {
        if (!user.enabled()) {
            audit(user.getUsername(), ip, false, LoginResult.ACCOUNT_DISABLED);
            throw BizException.unauthorized("account is disabled");
        }
    }

    /**
     * Rotates the password of the caller and ends every existing session.
     *
     * @param username    authenticated user
     * @param oldPassword current password
     * @param newPassword new password
     */
    @Transactional(rollbackFor = Exception.class)
    public void changePassword(String username, String oldPassword, String newPassword) {
        AdminUser user = requireUser(username);
        if (user.directoryAccount()) {
            throw BizException.invalidParam(
                    "password of a single sign on account is managed by the directory");
        }
        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw BizException.unauthorized("current password does not match");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(NOT_REQUIRED);
        adminUserMapper.updateById(user);
        tokenStore.revokeAll(username);
        log.info("password changed, username={}", username);
    }

    /**
     * Loads the authenticated account.
     *
     * @param username authenticated user
     * @return account record
     */
    public AdminUser currentUser(String username) {
        return requireUser(username);
    }

    /**
     * Counts the consecutive failures of an account and of a source address inside the lock window.
     *
     * <p>Only failures newer than the last successful login of that account are consecutive, so a
     * successful login resets the counter without deleting audit history.
     *
     * @param username submitted user name
     * @param ip       source address
     * @return {@code true} when either counter reached the configured threshold
     */
    private boolean isLocked(String username, String ip) {
        int threshold = properties.getAuth().getMaxFailedAttempts();
        LocalDateTime windowStart = LocalDateTime.now().minusMinutes(properties.getAuth().getLockMinutes());
        return countFailures(username, null, windowStart) >= threshold
                || countFailures(null, ip, windowStart) >= threshold;
    }

    private long countFailures(String username, String ip, LocalDateTime windowStart) {
        LocalDateTime since = windowStart;
        if (username != null) {
            LoginAudit lastSuccess = loginAuditMapper.selectOne(new LambdaQueryWrapper<LoginAudit>()
                    .eq(LoginAudit::getUsername, username)
                    .eq(LoginAudit::getSuccess, SUCCESS_FLAG)
                    .orderByDesc(LoginAudit::getCreatedAt)
                    .last("limit 1"));
            if (lastSuccess != null && lastSuccess.getCreatedAt() != null
                    && lastSuccess.getCreatedAt().isAfter(since)) {
                since = lastSuccess.getCreatedAt();
            }
        }
        LambdaQueryWrapper<LoginAudit> wrapper = new LambdaQueryWrapper<LoginAudit>()
                .eq(LoginAudit::getSuccess, FAILURE_FLAG)
                // An unreachable domain controller is a failed attempt in the audit but not a suspicious
                // one. Counting it would let a single outage lock out every account that retried.
                .ne(LoginAudit::getReason, LoginResult.DIRECTORY_UNAVAILABLE)
                .gt(LoginAudit::getCreatedAt, since);
        if (username != null) {
            wrapper.eq(LoginAudit::getUsername, username);
        }
        if (ip != null) {
            wrapper.eq(LoginAudit::getIp, ip);
        }
        return loginAuditMapper.selectCount(wrapper);
    }

    private void audit(String username, String ip, boolean success, LoginResult reason) {
        LoginAudit record = new LoginAudit();
        record.setUsername(username);
        record.setIp(ip);
        record.setSuccess(success ? SUCCESS_FLAG : FAILURE_FLAG);
        record.setReason(reason);
        loginAuditMapper.insert(record);
    }

    private AdminUser findByUsername(String username) {
        return adminUserMapper.selectOne(new LambdaQueryWrapper<AdminUser>()
                .eq(AdminUser::getUsername, username)
                .last("limit 1"));
    }

    /**
     * Folds a submitted login name to the stored form.
     *
     * <p>Lower case, and any domain suffix the user pasted in is dropped: {@code zhang@corp.example.com}
     * and {@code Zhang} are one person, and the suffix is re-attached by the directory adapter from
     * configuration. Without this the same colleague would be provisioned several accounts.
     */
    private String normalize(String username) {
        if (username == null) {
            return "";
        }
        String trimmed = username.trim().toLowerCase();
        int at = trimmed.indexOf('@');
        return at > 0 ? trimmed.substring(0, at) : trimmed;
    }

    private AdminUser requireUser(String username) {
        AdminUser user = findByUsername(username);
        if (user == null) {
            throw BizException.unauthorized("session no longer valid");
        }
        return user;
    }
}
