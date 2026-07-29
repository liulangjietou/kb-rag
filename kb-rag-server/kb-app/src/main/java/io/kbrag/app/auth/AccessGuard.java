package io.kbrag.app.auth;

import io.kbrag.common.exception.BizException;
import io.kbrag.domain.context.UserContextHolder;
import io.kbrag.domain.model.UserPrincipal;

import java.util.Set;

/**
 * Authorisation checks against the caller bound to the current request.
 *
 * <p>Static rather than a bean so a service can assert a data scope in one line without taking another
 * constructor dependency. The state it reads lives in {@link UserContextHolder}, which the authentication
 * interceptor binds and clears; nothing is held here.
 *
 * <p>Function level codes are enforced once, declaratively, at the web layer. What is enforced here is the
 * other half: whether the <em>particular</em> knowledge base a call names is inside the caller's scope. That
 * check needs the request body or a path variable, so it cannot be hoisted into an annotation, and it has to
 * sit next to the data - a scope check in a controller only guards the paths someone remembered to guard.
 *
 * @author owlzhangfq@gmail.com
 */
public final class AccessGuard {

    private AccessGuard() {
    }

    /**
     * The caller of the current request.
     *
     * @return bound caller
     * @throws BizException unauthorized outside an authenticated request
     */
    public static UserPrincipal currentUser() {
        UserPrincipal principal = UserContextHolder.get();
        if (principal == null) {
            throw BizException.unauthorized("no authenticated caller in context");
        }
        return principal;
    }

    /**
     * The caller of the current request, or {@code null} for an unauthenticated one.
     *
     * <p>For code shared between the console and the API key path, where the absence of a console caller
     * is normal rather than an error.
     *
     * @return bound caller or {@code null}
     */
    public static UserPrincipal currentUserOrNull() {
        return UserContextHolder.get();
    }

    /**
     * Asserts that the caller carries a permission code.
     *
     * @param code required permission code
     * @throws BizException forbidden when it is not granted
     */
    public static void requirePermission(String code) {
        if (!currentUser().hasPermission(code)) {
            throw BizException.forbidden("permission required: " + code);
        }
    }

    /**
     * Asserts that one knowledge base is inside the data scope of the caller.
     *
     * <p>Reported as forbidden rather than as not found. Hiding the base behind a 404 would leak less, but
     * it would also make every legitimate "I cannot see this base" ticket indistinguishable from a broken
     * link, and the set of knowledge base names is not the secret here - their contents are.
     *
     * @param kbId knowledge base business id
     * @throws BizException forbidden when the base is outside the scope
     */
    public static void requireKbAccess(String kbId) {
        if (!currentUser().canAccessKb(kbId)) {
            throw BizException.forbidden("knowledge base outside your data scope: " + kbId);
        }
    }

    /**
     * Asserts that every knowledge base of a set is inside the data scope of the caller.
     *
     * <p>All or nothing on purpose: silently dropping the bases a caller may not see would answer a
     * multi base search with a result that looks complete and is not.
     *
     * @param kbIds knowledge base business ids
     * @throws BizException forbidden when any base is outside the scope
     */
    public static void requireKbAccess(Set<String> kbIds) {
        if (kbIds == null) {
            return;
        }
        for (String kbId : kbIds) {
            requireKbAccess(kbId);
        }
    }

    /**
     * Whether the current request has a caller whose scope covers every knowledge base.
     *
     * <p>Also {@code true} when there is no console caller at all, which is the API key path: an outbound
     * call is already scoped by the application version it targets, so trimming it against a console data
     * scope that does not exist would filter everything away.
     *
     * @return {@code true} when no knowledge base trimming applies
     */
    public static boolean unrestrictedKbScope() {
        UserPrincipal principal = UserContextHolder.get();
        return principal == null || principal.kbScopeAll();
    }

    /**
     * Knowledge bases the current caller may read.
     *
     * <p>Only meaningful when {@link #unrestrictedKbScope()} is {@code false}; callers must test that
     * first, because an empty set here means "sees nothing", not "sees everything".
     *
     * @return visible knowledge base ids
     */
    public static Set<String> visibleKbIds() {
        UserPrincipal principal = UserContextHolder.get();
        return principal == null ? Set.of() : principal.kbIds();
    }
}
