package io.kbrag.domain.enums;

/**
 * Which entry point a login attempt came through.
 *
 * <p>The mode is submitted by the client instead of being inferred from the account, because it has to be
 * known before any account is looked up: single sign on may have to provision one, and the local path must
 * never provision anything.
 *
 * <p>The two are not interchangeable, and the check runs both ways. A directory account has no local hash
 * to compare, so the local path cannot serve it. More importantly the reverse is refused too: were the
 * directory allowed to authenticate a local account, anyone holding a domain account whose login name
 * happened to match the local administrator would inherit it.
 *
 * @author owlzhangfq@gmail.com
 */
public enum LoginMode {

    /** Password compared against the local BCrypt hash. */
    LOCAL,

    /** Password verified by a bind against the corporate directory. */
    SSO;

    /**
     * Parses the literal submitted by the console, case insensitively.
     *
     * @param value submitted literal, {@code null} or blank reads as {@link #LOCAL}
     * @return parsed mode
     */
    public static LoginMode from(String value) {
        if (value == null || value.isBlank()) {
            return LOCAL;
        }
        return valueOf(value.trim().toUpperCase());
    }
}
