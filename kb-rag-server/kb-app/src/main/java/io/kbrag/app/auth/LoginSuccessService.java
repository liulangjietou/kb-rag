package io.kbrag.app.auth;

import io.kbrag.domain.entity.AdminUser;
import io.kbrag.domain.entity.LoginAudit;
import io.kbrag.domain.enums.LoginResult;
import io.kbrag.domain.enums.UserSource;
import io.kbrag.domain.mapper.AdminUserMapper;
import io.kbrag.domain.mapper.LoginAuditMapper;
import io.kbrag.domain.model.ExternalIdentity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 已通过身份校验后的登录成功事务边界。
 *
 * <p>失败路径不能在等待 JVM 登录锁时占用数据库连接；因此 {@link AuthService} 负责在无事务
 * 状态下完成锁定、凭据校验和失败审计，只有确认身份后才进入本服务。自动建号、目录角色同步、
 * 成功审计、最后登录时间和会话签发在同一事务中完成。
 *
 * @author owlzhangfq@gmail.com
 */
@Service
@RequiredArgsConstructor
public class LoginSuccessService {

    private static final int SUCCESS_FLAG = 1;

    private final AdminUserMapper adminUserMapper;
    private final LoginAuditMapper loginAuditMapper;
    private final TokenStore tokenStore;
    private final UserService userService;
    private final DirectoryGroupSyncService groupSyncService;
    private final PrincipalResolver principalResolver;

    /**
     * 为已存在且已校验口令的账号签发会话。
     *
     * @param user     已验证账号
     * @param username 标准化用户名
     * @param ip       已解析来源地址
     * @return 登录票据
     */
    @Transactional(rollbackFor = Exception.class)
    public LoginTicket issueExisting(AdminUser user, String username, String ip) {
        return issue(user, username, ip);
    }

    /**
     * 在目录凭据通过后完成可选建号、角色同步与会话签发。
     *
     * @param existing 已有目录账号，首次登录时为 {@code null}
     * @param username 标准化用户名
     * @param groupDns 目录返回的用户组
     * @param ip       已解析来源地址
     * @return 登录票据
     */
    @Transactional(rollbackFor = Exception.class)
    public LoginTicket issueDirectory(AdminUser existing, String username, List<String> groupDns, String ip) {
        AdminUser user = existing == null ? userService.provisionDirectoryUser(username) : existing;
        if (groupSyncService.enabled()) {
            // 必须在 token 前同步，打开的新会话才能立即看到本次目录登录派生的角色。
            groupSyncService.sync(user, groupDns);
        }
        return issue(user, username, ip);
    }

    /**
     * 在浏览器身份提供方断言通过后完成可选建号与会话签发。
     *
     * @param existing 已有同源账号，首次登录时为 {@code null}
     * @param username 标准化用户名
     * @param source   身份提供方类型
     * @param identity 已验证身份
     * @param ip       已解析来源地址
     * @return 登录票据
     */
    @Transactional(rollbackFor = Exception.class)
    public LoginTicket issueExternal(AdminUser existing, String username, UserSource source,
                                     ExternalIdentity identity, String ip) {
        AdminUser user = existing == null
                ? userService.provisionExternalUser(
                        username, source, identity.displayName(), identity.email())
                : existing;
        return issue(user, username, ip);
    }

    private LoginTicket issue(AdminUser user, String username, String ip) {
        LoginAudit record = new LoginAudit();
        record.setUsername(username);
        record.setIp(ip);
        record.setSuccess(SUCCESS_FLAG);
        record.setReason(LoginResult.SUCCESS);
        loginAuditMapper.insert(record);

        user.setLastLoginAt(LocalDateTime.now());
        adminUserMapper.updateById(user);
        // 首次建号没有权限缓存；已有账号也可能在离线期间被重新授权。
        principalResolver.evict(username);
        return new LoginTicket(tokenStore.issue(username), user.mustChangePassword());
    }
}
