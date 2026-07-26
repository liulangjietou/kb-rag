package io.kbrag.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * API key status payload.
 *
 * @param status {@code ENABLED} or {@code DISABLED}
 *
 * @author owlzhangfq@gmail.com
 */
public record UpdateApiKeyStatusRequest(
        @NotBlank(message = "must not be blank") String status) {
}
