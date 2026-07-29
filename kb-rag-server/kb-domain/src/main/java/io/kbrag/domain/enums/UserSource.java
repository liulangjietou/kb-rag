package io.kbrag.domain.enums;

/**
 * Where a console account came from, which decides how its password is verified.
 *
 * <p>Not a boolean flag on purpose: the two sources differ in more than one behaviour - a directory
 * account carries no local hash, cannot rotate its password through the console and is re-synced from
 * the directory on every login - and a named source keeps those rules readable at each branch.
 *
 * @author owlzhangfq@gmail.com
 */
public enum UserSource {

    /** Created in the console by an operator; the password is a local BCrypt hash. */
    LOCAL,

    /** Provisioned on the first successful directory bind; the password never reaches this system. */
    LDAP;

    /**
     * Resolves a source from its literal, case insensitively.
     *
     * @param value request literal
     * @return matching source
     * @throws IllegalArgumentException when the literal matches no source
     */
    public static UserSource from(String value) {
        if (value != null) {
            for (UserSource source : values()) {
                if (source.name().equalsIgnoreCase(value.trim())) {
                    return source;
                }
            }
        }
        throw new IllegalArgumentException("unknown user source: " + value);
    }
}
