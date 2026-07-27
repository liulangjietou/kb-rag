package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Password rotation payload.
 *
 * @param oldPassword current password
 * @param newPassword new password, at least eight characters
 *
 * @author owlzhangfq@gmail.com
 */
public record ChangePasswordRequest(
        @JsonProperty("old_password") @NotBlank(message = "must not be blank") String oldPassword,
        @JsonProperty("new_password") @NotBlank(message = "must not be blank")
        @Size(min = 8, message = "must be at least 8 characters") String newPassword) {
}
