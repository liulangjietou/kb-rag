package io.kbrag.app.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.AdminUser;
import io.kbrag.domain.entity.LoginAudit;
import io.kbrag.domain.entity.Tenant;
import io.kbrag.domain.enums.DirectoryBindResult;
import io.kbrag.domain.enums.LoginMode;
import io.kbrag.domain.enums.LoginResult;
import io.kbrag.domain.enums.UserSource;
import io.kbrag.domain.mapper.AdminUserMapper;
import io.kbrag.domain.mapper.LoginAuditMapper;
import io.kbrag.domain.mapper.TenantMapper;
import io.kbrag.domain.model.DirectoryBindOutcome;
import io.kbrag.domain.model.ExternalIdentity;
import io.kbrag.domain.port.DirectoryAuthenticator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Locale;

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
    private static final String INVALID_CREDENTIALS_MESSAGE = "invalid username or password";
    // 不对应任何真实账号，只用于让未知账号和错误登录入口也付出同等 BCrypt 成本。
    static final String DUMMY_PASSWORD_HASH =
            "$2a$10$tesHlq8Mb9Tj200DjJwf6Om6ibfxNmblzIJp2uQ0goV2Qmm0uCt4W";

    private final AdminUserMapper adminUserMapper;
    private final LoginAuditMapper loginAuditMapper;
    private final TenantMapper tenantMapper;
    private final ConsoleSessionService consoleSessionService;
    private final KbProperties properties;
    private final BCryptPasswordEncoder passwordEncoder;
    private final DirectoryAuthenticator directoryAuthenticator;
    private final LoginFailureAuditService loginFailureAuditService;
    private final LoginSuccessService loginSuccessService;
    private final LoginAttemptGuard loginAttemptGuard;

    /**
     * Verifies credentials through the requested entry point and issues a session token.
     *
     * @param username submitted user name
     * @param password submitted password
     * @param mode     entry point the attempt came through
     * @param ip       source address of the attempt
     * @return session ticket
     */
    @Transactional(propagation = Propagation.NEVER)
    public LoginTicket login(String username, String password, LoginMode mode, String ip) {
        // 完整邮箱精确命中时优先作为本地登录名；否则回退到旧版去 @ 后缀语义。
        // 锁前查询只决定规范化键，绝不把账号或密码摘要带进锁内复用。回退在 LoginAttemptGuard
        // 前完成，使 admin@a 和 admin@b 共用 admin 的失败计数键。
        String login = mode == LoginMode.SSO
                ? normalizeExternalLogin(username) : resolveLocalLogin(username);
        try (LoginAttemptGuard.Permit ignored = loginAttemptGuard.acquire(login, ip)) {
            if (isLocked(login, ip)) {
                audit(login, ip, LoginResult.ACCOUNT_LOCKED);
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
        // 必须在用户名/IP guard 内重读当前行。锁前缓存实体会让排队请求在改密或停用后继续
        // 使用旧 BCrypt 摘要，并可能签发一枚新 token。
        AdminUser user = findByUsername(username);
        boolean localAccount = user != null && !user.directoryAccount();
        String passwordHash = localAccount && user.getPasswordHash() != null
                ? user.getPasswordHash()
                : DUMMY_PASSWORD_HASH;
        boolean passwordMatches = passwordEncoder.matches(password, passwordHash);
        if (user == null) {
            audit(username, ip, LoginResult.USER_NOT_FOUND);
            throw BizException.unauthorized(INVALID_CREDENTIALS_MESSAGE);
        }
        if (user.directoryAccount()) {
            audit(username, ip, LoginResult.WRONG_LOGIN_MODE);
            throw BizException.unauthorized(INVALID_CREDENTIALS_MESSAGE);
        }
        if (user.getPasswordHash() == null || !passwordMatches) {
            audit(username, ip, LoginResult.BAD_PASSWORD);
            throw BizException.unauthorized(INVALID_CREDENTIALS_MESSAGE);
        }
        requireEnabled(user, ip);
        return loginSuccessService.issueExisting(user, username, ip);
    }

    private LoginTicket loginWithDirectory(String username, String password, String ip) {
        if (!directoryAuthenticator.available()) {
            audit(username, ip, LoginResult.SSO_DISABLED);
            throw BizException.unauthorized("single sign on is not enabled");
        }
        AdminUser existing = findByUsername(username);
        // Checked before the bind: only an account born from the directory may be authenticated by
        // it. A local account whose name matches a domain account would otherwise be inherited, and
        // an account bound to another identity provider must not have a second door.
        if (existing != null && existing.getSource() != UserSource.LDAP) {
            audit(username, ip, LoginResult.WRONG_LOGIN_MODE);
            throw BizException.unauthorized(INVALID_CREDENTIALS_MESSAGE);
        }
        // Also checked before the bind, so a suspended account cannot be used to probe the directory.
        if (existing != null) {
            requireEnabled(existing, ip);
        }

        DirectoryBindOutcome bind = directoryAuthenticator.bind(username, password);
        if (bind.result() == DirectoryBindResult.SERVICE_UNAVAILABLE) {
            // Audited as a failure but never counted towards the lockout, which is why the reason is a
            // separate value: one domain controller outage would otherwise lock out everyone who retried.
            audit(username, ip, LoginResult.DIRECTORY_UNAVAILABLE);
            throw BizException.unauthorized("directory is unavailable, try again later");
        }
        if (bind.result() == DirectoryBindResult.INVALID_CREDENTIALS) {
            audit(username, ip, LoginResult.DIRECTORY_REJECTED);
            throw BizException.unauthorized(INVALID_CREDENTIALS_MESSAGE);
        }

        return loginSuccessService.issueDirectory(existing, username, bind.groupDns(), ip);
    }

    /**
     * Lands a verified single sign on assertion: matches or provisions the account and issues a
     * session token.
     *
     * <p>The assertion is already verified by the protocol adapter when this runs; what is decided
     * here is whether the asserted person maps to a console account that may open a session. The
     * checks mirror the directory path exactly - same lock window, same source discipline, same
     * audit rows - because to the rest of the system these are all just logins.
     *
     * @param source   protocol the assertion arrived through
     * @param identity identity the provider asserted
     * @param ip       source address of the callback
     * @return session ticket
     */
    @Transactional(propagation = Propagation.NEVER)
    public LoginTicket completeExternalLogin(UserSource source, ExternalIdentity identity, String ip) {
        String login = normalizeExternalLogin(identity.username());
        if (login.isBlank()) {
            // A verified assertion naming nobody is a provider misconfiguration, not a user error,
            // but it still must not mint a session keyed on an empty name.
            log.error("single sign on assertion carries no usable login name, source={}", source);
            throw BizException.unauthorized("identity provider asserted no login name");
        }
        try (LoginAttemptGuard.Permit ignored = loginAttemptGuard.acquire(login, ip)) {
            if (isLocked(login, ip)) {
                audit(login, ip, LoginResult.ACCOUNT_LOCKED);
                throw BizException.unauthorized("account temporarily locked, retry after "
                        + properties.getAuth().getLockMinutes() + " minutes");
            }
            AdminUser existing = findByUsername(login);
            // An account is bound to the entry point that created it. Letting a SAML assertion open an
            // OIDC account would make every configured provider a master key for every other.
            if (existing != null && existing.getSource() != source) {
                audit(login, ip, LoginResult.WRONG_LOGIN_MODE);
                throw BizException.unauthorized(INVALID_CREDENTIALS_MESSAGE);
            }
            if (existing != null) {
                requireEnabled(existing, ip);
            }
            LoginTicket ticket = loginSuccessService.issueExternal(existing, login, source, identity, ip);
            log.info("single sign on login succeeded, username={}, source={}, ip={}", login, source, ip);
            return ticket;
        }
    }

    private void requireEnabled(AdminUser user, String ip) {
        if (!user.enabled()) {
            audit(user.getUsername(), ip, LoginResult.ACCOUNT_DISABLED);
            throw BizException.unauthorized(INVALID_CREDENTIALS_MESSAGE);
        }
        // Refused at the door, not only by the resolver: a disabled tenant means every one of its
        // accounts stops working, and letting the login succeed just to be rejected on the first
        // request would issue a token for a session that can never be used.
        Tenant tenant = tenantMapper.selectOne(new LambdaQueryWrapper<Tenant>()
                .eq(Tenant::getTenantId, user.getTenantId())
                .last("limit 1"));
        if (tenant != null && !tenant.enabled()) {
            audit(user.getUsername(), ip, LoginResult.TENANT_DISABLED);
            throw BizException.unauthorized(INVALID_CREDENTIALS_MESSAGE);
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
        if (adminUserMapper.updateById(user) != 1) {
            throw BizException.invalidParam("user was updated concurrently; retry");
        }
        consoleSessionService.revokeAll(username);
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
                // A request rejected by an existing lock is observable, but it is not a new credential
                // failure. Counting it would let polling extend the lock window indefinitely.
                .ne(LoginAudit::getReason, LoginResult.ACCOUNT_LOCKED)
                .gt(LoginAudit::getCreatedAt, since);
        if (username != null) {
            wrapper.eq(LoginAudit::getUsername, username);
        }
        if (ip != null) {
            wrapper.eq(LoginAudit::getIp, ip);
        }
        return loginAuditMapper.selectCount(wrapper);
    }

    private void audit(String username, String ip, LoginResult reason) {
        loginFailureAuditService.record(username, ip, reason);
    }

    private AdminUser findByUsername(String username) {
        return adminUserMapper.selectOne(new LambdaQueryWrapper<AdminUser>()
                .eq(AdminUser::getUsername, username)
                .last("limit 1"));
    }

    private String resolveLocalLogin(String username) {
        String exactLogin = normalizeLocalLogin(username);
        if (findByUsername(exactLogin) != null || exactLogin.indexOf('@') <= 0) {
            return exactLogin;
        }
        return normalizeExternalLogin(exactLogin);
    }

    /** 本地账号统一折叠大小写，但完整保留邮箱域名。 */
    private String normalizeLocalLogin(String username) {
        if (username == null) {
            return "";
        }
        return username.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 折叠目录或外部身份的登录名，并沿用既有的去域后缀规则。
     *
     * <p>{@code zhang@corp.example.com} 和 {@code Zhang} 在目录中是同一个人，后缀由目录适配器
     * 根据配置重新附加。该规则不能用于本地邮箱账号，否则会把不同邮箱错误地合并。
     */
    private String normalizeExternalLogin(String username) {
        String trimmed = normalizeLocalLogin(username);
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
