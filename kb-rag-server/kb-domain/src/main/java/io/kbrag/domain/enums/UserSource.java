package io.kbrag.domain.enums;

/**
 * Where a console account came from, which decides how its identity is verified.
 *
 * <p>Not a boolean flag on purpose: the sources differ in more than one behaviour - an external
 * account carries no local hash, cannot rotate its password through the console and may be re-synced
 * from its identity source on every login - and a named source keeps those rules readable at each
 * branch. Each single sign on protocol is its own source because an account is pinned to the entry
 * point that verified it first: letting an OIDC account in through the SAML endpoint would make every
 * configured identity provider an equally valid door to every account.
 *
 * @author owlzhangfq@gmail.com
 */
public enum UserSource {

    /** Created in the console by an operator; the password is a local BCrypt hash. */
    LOCAL,

    /** Provisioned on the first successful directory bind; the password never reaches this system. */
    LDAP,

    /** Provisioned on the first OpenID Connect callback; verified by the IdP's signed id_token. */
    OIDC,

    /** Provisioned on the first SAML assertion; verified by the IdP's XML signature. */
    SAML,

    /** Provisioned on the first CAS ticket validation; verified against the CAS server. */
    CAS;

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
