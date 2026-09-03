package io.kbrag.app.auth;

import cn.dev33.satoken.stp.StpUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 控制台会话的签发与吊销。
 *
 * <p>这个类的前身是自建的 {@code TokenStore}：它自己生成随机令牌、自己算过期、自己写库、自己维护一份
 * 进程内缓存。换成 Sa-Token 之后这些全部由框架承担，因此类名也从"存储"改成了"服务"——存储职责如今在
 * {@code SaTokenDao} 那一侧（{@code MysqlSaTokenDao} 或官方 Redis 适配），留着旧名字会让人来这里找
 * 根本不在这里的存储逻辑。
 *
 * <p><b>为什么还要这层门面，而不是让调用方直接用 {@link StpUtil}。</b> 会话吊销散落在五个业务动作里
 * ——改密码、禁用账号、删除账号、改角色、重置密码——它们关心的是"这个人的登录态必须立刻作废"，不是
 * "调用哪个静态方法"。把语义收在这里，一是让"改密码要踢下线"这类规则在一个地方可读，二是这五处业务
 * 代码不必因为将来换会话框架而再改一遍。
 *
 * <p>会话以登录名为标识，与审计表、令牌表历来的口径一致。
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
public class ConsoleSessionService {

    /**
     * 为一个已通过身份校验的账号签发会话。
     *
     * <p>只在登录成功之后调用：这个方法不做任何凭据判断，它的前提是调用方已经确认了身份。
     *
     * @param username 已认证的登录名
     * @return 本次会话的令牌
     */
    public String issue(String username) {
        StpUtil.login(username);
        return StpUtil.getTokenValue();
    }

    /**
     * 作废一个会话，用于显式登出。
     *
     * @param token 要作废的令牌
     */
    public void revoke(String token) {
        StpUtil.logoutByTokenValue(token);
    }

    /**
     * 作废一个账号的全部会话。
     *
     * <p>改密码、停用、删除、改授权都要走这里。作废的是"这个人现在所有还开着的会话"，而不只是发起本次
     * 操作的那一个：一个被停用的账号如果还能用另一台设备上的旧令牌继续操作，停用就没有发生。
     *
     * @param username 会话要被终止的登录名
     */
    public void revokeAll(String username) {
        StpUtil.logout(username);
        log.info("sessions revoked, username={}", username);
    }
}
