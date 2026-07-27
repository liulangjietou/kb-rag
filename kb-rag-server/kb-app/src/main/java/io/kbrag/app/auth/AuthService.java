package io.kbrag.app.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.AdminUser;
import io.kbrag.domain.entity.LoginAudit;
import io.kbrag.domain.enums.LoginResult;
import io.kbrag.domain.mapper.AdminUserMapper;
import io.kbrag.domain.mapper.LoginAuditMapper;
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

    /**
     * Verifies credentials and issues a session token.
     *
     * @param username submitted user name
     * @param password submitted password
     * @param ip       source address of the attempt
     * @return session ticket
     */
    @Transactional(rollbackFor = Exception.class)
    public LoginTicket login(String username, String password, String ip) {
        if (isLocked(username, ip)) {
            audit(username, ip, false, LoginResult.ACCOUNT_LOCKED);
            log.info("login rejected by lock window, username={}, ip={}", username, ip);
            throw BizException.unauthorized("account temporarily locked, retry after "
                    + properties.getAuth().getLockMinutes() + " minutes");
        }
        AdminUser user = findByUsername(username);
        if (user == null) {
            audit(username, ip, false, LoginResult.USER_NOT_FOUND);
            throw BizException.unauthorized("invalid username or password");
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            audit(username, ip, false, LoginResult.BAD_PASSWORD);
            throw BizException.unauthorized("invalid username or password");
        }
        audit(username, ip, true, LoginResult.SUCCESS);
        user.setLastLoginAt(LocalDateTime.now());
        adminUserMapper.updateById(user);
        log.info("login succeeded, username={}, ip={}", username, ip);
        return new LoginTicket(tokenStore.issue(username), user.mustChangePassword());
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

    private AdminUser requireUser(String username) {
        AdminUser user = findByUsername(username);
        if (user == null) {
            throw BizException.unauthorized("session no longer valid");
        }
        return user;
    }
}
