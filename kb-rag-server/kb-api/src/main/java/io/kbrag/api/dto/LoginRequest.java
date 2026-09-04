package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Login payload.
 *
 * @param username submitted user name
 * @param password submitted password
 * @param mode     which entry point the attempt came through, {@code LOCAL} or {@code SSO};
 *                 absent reads as {@code LOCAL} so existing clients keep working
 * @param captchaProof one-time proof issued after a valid slider track
 *
 * @author owlzhangfq@gmail.com
 */
public record LoginRequest(
        @NotBlank(message = "must not be blank")
        @Size(max = 254, message = "must not exceed 254 characters") String username,
        @NotBlank(message = "must not be blank")
        @Size(max = 512, message = "must not exceed 512 characters") String password,
        @Size(max = 16, message = "must not exceed 16 characters") String mode,
        @NotBlank(message = "must not be blank")
        @Size(min = 43, max = 43, message = "must contain exactly 43 characters")
        @JsonProperty("captcha_proof") String captchaProof) {
}
