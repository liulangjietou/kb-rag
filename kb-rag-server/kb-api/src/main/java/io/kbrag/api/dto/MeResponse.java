package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Authenticated account view. The password hash is never part of it.
 *
 * @param username           login name
 * @param mustChangePassword {@code true} while the bootstrap password has not been rotated
 * @param lastLoginAt        ISO timestamp of the previous successful login, {@code null} on first login
 *
 * @author owlzhangfq@gmail.com
 */
public record MeResponse(
        String username,
        @JsonProperty("must_change_password") boolean mustChangePassword,
        @JsonProperty("last_login_at") String lastLoginAt) {
}
