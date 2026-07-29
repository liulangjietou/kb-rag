package io.kbrag.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Login payload.
 *
 * @param username submitted user name
 * @param password submitted password
 * @param mode     which entry point the attempt came through, {@code LOCAL} or {@code SSO};
 *                 absent reads as {@code LOCAL} so existing clients keep working
 *
 * @author owlzhangfq@gmail.com
 */
public record LoginRequest(
        @NotBlank(message = "must not be blank") String username,
        @NotBlank(message = "must not be blank") String password,
        String mode) {
}
