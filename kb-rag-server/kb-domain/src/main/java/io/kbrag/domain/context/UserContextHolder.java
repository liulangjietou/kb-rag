package io.kbrag.domain.context;

import io.kbrag.domain.model.UserPrincipal;

/**
 * Holder for the console caller of the current request.
 *
 * <p>Bound by the authentication interceptor and read by service code that needs the caller without
 * having it threaded through every signature. A thread local rather than a request scoped bean because
 * the value is also read from code that is not aware of the servlet layer, and because the domain module
 * must not depend on the web stack.
 *
 * <p>Asynchronous work never inherits the binding. That is deliberate: a background reindex outlives the
 * request that queued it, and letting it authorise itself against a caller who has logged out since would
 * be a permission check that lies. Such jobs record the operator they were queued by and run unguarded.
 *
 * @author owlzhangfq@gmail.com
 */
public final class UserContextHolder {

    private static final ThreadLocal<UserPrincipal> CURRENT = new ThreadLocal<>();

    private UserContextHolder() {
    }

    /**
     * Reads the caller bound to the current thread.
     *
     * @return current caller, or {@code null} outside of an authenticated request
     */
    public static UserPrincipal get() {
        return CURRENT.get();
    }

    /**
     * Binds a caller to the current thread.
     *
     * @param principal resolved caller
     */
    public static void set(UserPrincipal principal) {
        CURRENT.set(principal);
    }

    /** Clears the binding, must be called in a finally block by the entry interceptor. */
    public static void clear() {
        CURRENT.remove();
    }
}
