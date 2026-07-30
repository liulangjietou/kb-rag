package io.kbrag.domain.enums;

/**
 * Who put a role binding into {@code t_kb_user_role}.
 *
 * <p>The distinction exists so directory group synchronisation can replace its own grants wholesale
 * without ever touching an operator's. A manually granted role silently revoked by a night time login
 * is the kind of incident nobody can trace; a directory grant that survives leaving the group makes
 * the synchronisation pointless. Both mistakes are prevented by the same column.
 *
 * @author owlzhangfq@gmail.com
 */
public enum RoleGrantSource {

    /** Granted by an operator on the user management screen. Never touched by synchronisation. */
    MANUAL,

    /** Derived from directory group membership. Replaced in full on every single sign on login. */
    LDAP_SYNC
}
