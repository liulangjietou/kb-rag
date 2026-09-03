package io.kbrag.api.filter;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.stp.StpUtil;
import io.kbrag.app.auth.PrincipalResolver;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.context.UserContextHolder;
import io.kbrag.domain.context.ModelUsageContextHolder;
import io.kbrag.domain.model.ModelUsageContext;
import io.kbrag.domain.model.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 校验每个管理接口调用的登录态，并把调用者绑定到本次请求。
 *
 * <p>登录与单点登录入口由 MVC 注册排除在外；管理 API 下的其余路径都需要一个有效会话。Actuator 跑在自己
 * 那条只监听回环地址的管理端口上，因此本拦截器的 {@code /api/**} 与 {@code /internal/**} 范围本就够不到它。
 *
 * <p>会话令牌走自定义请求头而非 Cookie，跨站请求伪造因此在构造上就不成立——浏览器不会替攻击者带上一个
 * 自定义头。这条性质在换用 Sa-Token 之后靠 {@code sa-token.is-read-cookie=false} 继续保持，不是自动获得的。
 *
 * <p>调用者的权限在这里一次性解析完毕，而不是在各处按需查询，这样一次请求不会在执行到一半时看见权限变更。
 *
 * @author owlzhangfq@gmail.com
 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    /** 存放已认证登录名的请求属性。 */
    public static final String ATTR_USERNAME = "kb.username";

    /** 存放原始令牌的请求属性，登出接口需要它。 */
    public static final String ATTR_TOKEN = "kb.token";

    private final PrincipalResolver principalResolver;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        try {
            StpUtil.checkLogin();
        } catch (NotLoginException e) {
            // 统一翻译成项目自己的错误信封：前端按 401 加消息渲染，不认识框架的异常类型。
            throw BizException.unauthorized(describe(e));
        }
        String username = StpUtil.getLoginIdAsString();
        request.setAttribute(ATTR_USERNAME, username);
        request.setAttribute(ATTR_TOKEN, StpUtil.getTokenValue());
        UserPrincipal principal = principalResolver.resolve(username);
        UserContextHolder.set(principal);
        ModelUsageContextHolder.set(new ModelUsageContext(
                principal.tenantId(), ModelUsageContext.SOURCE_CONSOLE, principal.userId()));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                               Exception ex) {
        // 容器线程是复用的，留下未解绑的条目会被下一个拿到该线程的请求读到。放在 afterCompletion 而不是
        // postHandle，因为后者在抛异常时会被跳过。
        UserContextHolder.clear();
        ModelUsageContextHolder.clear();
    }

    /**
     * 把框架的未登录原因翻译成给人看的消息。
     *
     * <p>原先只能分辨"没带令牌"和"令牌无效"两种情况。被顶下线和被管理员踢下线在过去都只能报成令牌失效，
     * 让人以为是会话到期；现在它们各自有准确的说法，用户看到的是发生了什么，而不是一个笼统的过期提示。
     */
    private String describe(NotLoginException e) {
        return switch (e.getType()) {
            case NotLoginException.NOT_TOKEN -> "missing bearer token";
            case NotLoginException.BE_REPLACED -> "signed in from another location";
            case NotLoginException.KICK_OUT -> "session ended by an administrator";
            default -> "token expired or invalid";
        };
    }
}
