package io.kbrag.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Login payload.
 *
 * @param username submitted user name
 * @param password submitted password
 */
public record LoginRequest(
        @NotBlank(message = "must not be blank") String username,
        @NotBlank(message = "must not be blank") String password) {
}
