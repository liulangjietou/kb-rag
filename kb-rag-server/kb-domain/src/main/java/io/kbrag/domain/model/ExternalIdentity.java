package io.kbrag.domain.model;

/**
 * The identity an external provider asserted for one login.
 *
 * <p>Only what the console actually consumes is carried: a login name to key the account on, and the
 * two display fields shown on the user management page. Whatever else the provider asserted - groups,
 * claims, attributes - stays at the protocol adapter, because an unconsumed field flowing through the
 * application layer is a field someone will eventually consume by accident.
 *
 * @param username    login name as asserted, not yet normalised
 * @param displayName human readable name, may be {@code null} when the provider asserted none
 * @param email       contact address, may be {@code null}
 *
 * @author owlzhangfq@gmail.com
 */
public record ExternalIdentity(String username, String displayName, String email) {
}
