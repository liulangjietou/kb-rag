package io.kbrag.api.filter;

import io.kbrag.app.auth.PrincipalResolver;
import io.kbrag.app.auth.TokenStore;
import io.kbrag.common.constant.KbConstants;
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

import java.util.Optional;

/**
 * Verifies the bearer token of every management call and binds the caller for the rest of the request.
 *
 * <p>The login and single sign-on entry points are excluded by the MVC registration; everything else
 * under the management API needs a valid token. Actuator runs on its own loopback-only management
 * listener and is therefore outside this interceptor's {@code /api/**} and {@code /internal/**} scope.
 * Using a custom header instead of a cookie removes cross site request forgery from the threat model
 * entirely.
 *
 * <p>The permissions of the caller are resolved once here rather than wherever they happen to be needed,
 * so a single request cannot see a grant change take effect halfway through it.
 *
 * @author owlzhangfq@gmail.com
 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    /** Request attribute holding the authenticated user name. */
    public static final String ATTR_USERNAME = "kb.username";

    /** Request attribute holding the raw token, needed by the logout endpoint. */
    public static final String ATTR_TOKEN = "kb.token";

    private final TokenStore tokenStore;
    private final PrincipalResolver principalResolver;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String header = request.getHeader(KbConstants.AUTHORIZATION_HEADER);
        if (header == null || !header.startsWith(KbConstants.BEARER_PREFIX)) {
            throw BizException.unauthorized("missing bearer token");
        }
        String token = header.substring(KbConstants.BEARER_PREFIX.length()).trim();
        Optional<String> username = tokenStore.resolve(token);
        if (username.isEmpty()) {
            throw BizException.unauthorized("token expired or invalid");
        }
        request.setAttribute(ATTR_USERNAME, username.get());
        request.setAttribute(ATTR_TOKEN, token);
        UserPrincipal principal = principalResolver.resolve(username.get());
        UserContextHolder.set(principal);
        ModelUsageContextHolder.set(new ModelUsageContext(
                principal.tenantId(), ModelUsageContext.SOURCE_CONSOLE, principal.userId()));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                               Exception ex) {
        // Container threads are pooled, so an unbound entry left behind would be read by whoever gets the
        // thread next. Cleared here rather than in postHandle, which a thrown exception skips.
        UserContextHolder.clear();
        ModelUsageContextHolder.clear();
    }
}
