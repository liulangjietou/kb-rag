package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Login result.
 *
 * @param token              opaque bearer token, valid for the configured session lifetime
 * @param mustChangePassword {@code true} while the bootstrap password has not been rotated
 *
 * @author owlzhangfq@gmail.com
 */
public record LoginResponse(
        String token,
        @JsonProperty("must_change_password") boolean mustChangePassword) {
}
