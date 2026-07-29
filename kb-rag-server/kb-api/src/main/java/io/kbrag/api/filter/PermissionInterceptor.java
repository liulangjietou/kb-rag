package io.kbrag.api.filter;

import io.kbrag.api.annotation.RequiresPermission;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.context.UserContextHolder;
import io.kbrag.domain.model.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enforces the {@link RequiresPermission} declaration of the handler about to run.
 *
 * <p>Registered after {@link AuthInterceptor}, which is what binds the caller this reads. An endpoint
 * carrying no declaration is left alone: authentication has already happened, and the endpoints that
 * legitimately need nothing more are the ones about the session itself.
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class PermissionInterceptor implements HandlerInterceptor {

    private static final String CODE_SEPARATOR = " or ";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        RequiresPermission required = method.getMethodAnnotation(RequiresPermission.class);
        if (required == null) {
            required = method.getBeanType().getAnnotation(RequiresPermission.class);
        }
        if (required == null || required.value().length == 0) {
            return true;
        }
        UserPrincipal principal = UserContextHolder.get();
        if (principal == null) {
            // Reached when a guarded path was left out of the interceptor registration rather than when a
            // caller misbehaved, so it is reported as a missing session and not as a denial.
            throw BizException.unauthorized("no authenticated caller in context");
        }
        if (principal.hasAnyPermission(required.value())) {
            return true;
        }
        throw BizException.forbidden("permission required: " + String.join(CODE_SEPARATOR, required.value()));
    }
}
