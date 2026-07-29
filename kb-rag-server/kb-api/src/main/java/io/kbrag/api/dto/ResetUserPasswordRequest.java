package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Password an operator sets on behalf of somebody who lost theirs.
 *
 * <p>The old password is not asked for, unlike the self service rotation: the point of this endpoint is that
 * nobody knows it any more. The account is then flagged to rotate again at next login.
 *
 * @param newPassword replacement password
 *
 * @author owlzhangfq@gmail.com
 */
public record ResetUserPasswordRequest(
        @JsonProperty("new_password")
        @NotBlank(message = "must not be blank")
        @Size(min = 8, max = 64, message = "must be between 8 and 64 characters")
        String newPassword) {
}
