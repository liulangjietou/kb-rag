package io.kbrag.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * New lifecycle state of a tenant.
 *
 * @param status {@code ENABLED} or {@code DISABLED}
 *
 * @author owlzhangfq@gmail.com
 */
public record UpdateTenantStatusRequest(
        @NotBlank(message = "must not be blank") String status) {
}
