package io.kbrag.domain.enums;

/**
 * Reason recorded in {@code t_kb_login_audit} for one login attempt.
 *
 * @author owlzhangfq@gmail.com
 */
public enum LoginResult {

    /** Credentials accepted. */
    SUCCESS,

    /** Unknown user name. */
    USER_NOT_FOUND,

    /** Password did not match the stored BCrypt hash. */
    BAD_PASSWORD,

    /** Account is inside the brute force lock window. */
    ACCOUNT_LOCKED,

    /** Credentials were correct but an operator suspended the account. */
    ACCOUNT_DISABLED,

    /** Credentials were correct but the tenant of the account is disabled. */
    TENANT_DISABLED,

    /** Directory rejected the bind: wrong domain password, or the domain account itself is locked. */
    DIRECTORY_REJECTED,

    /**
     * Domain controller could not be reached. Kept apart from {@link #DIRECTORY_REJECTED} because a
     * network fault must not be read as a failed credential, neither by the operator looking at the
     * audit page nor by the brute force counter.
     */
    DIRECTORY_UNAVAILABLE,

    /** Single sign-on was attempted while the directory integration is switched off. */
    SSO_DISABLED,

    /**
     * Local account tried the single sign-on entry point, or a directory account tried the local one.
     * The two entry points are not interchangeable: a directory account has no local hash to compare.
     */
    WRONG_LOGIN_MODE
}
