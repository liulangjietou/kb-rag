package io.kbrag.app.auth;

/**
 * Outcome of a successful login.
 *
 * @param token               opaque bearer token
 * @param mustChangePassword  {@code true} while the bootstrap password has not been rotated
 */
public record LoginTicket(String token, boolean mustChangePassword) {
}
