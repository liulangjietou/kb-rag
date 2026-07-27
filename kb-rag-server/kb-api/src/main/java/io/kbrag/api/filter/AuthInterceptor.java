package io.kbrag.api.filter;

import io.kbrag.app.auth.TokenStore;
import io.kbrag.common.constant.KbConstants;
import io.kbrag.common.exception.BizException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Optional;

/**
 * Verifies the bearer token of every management call.
 *
 * <p>The login endpoint and the actuator are excluded by the MVC registration; everything else needs a
 * valid token. Using a custom header instead of a cookie removes cross site request forgery from the
 * threat model entirely.
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
        return true;
    }
}
