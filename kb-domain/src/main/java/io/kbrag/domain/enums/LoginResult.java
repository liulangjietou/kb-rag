package io.kbrag.domain.enums;

/**
 * Reason recorded in {@code t_kb_login_audit} for one login attempt.
 */
public enum LoginResult {

    /** Credentials accepted. */
    SUCCESS,

    /** Unknown user name. */
    USER_NOT_FOUND,

    /** Password did not match the stored BCrypt hash. */
    BAD_PASSWORD,

    /** Account is inside the brute force lock window. */
    ACCOUNT_LOCKED
}
