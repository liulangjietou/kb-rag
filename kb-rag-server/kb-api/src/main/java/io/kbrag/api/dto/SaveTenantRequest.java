package io.kbrag.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Payload of a tenant creation.
 *
 * <p>The code is immutable after creation: the index naming tenant segment derives from the tenant
 * id, and operational runbooks refer to the code, so neither may drift.
 *
 * @param code stable code, letters, digits, hyphen and underscore
 * @param name display name
 *
 * @author owlzhangfq@gmail.com
 */
public record SaveTenantRequest(
        @NotBlank(message = "must not be blank")
        @Size(max = 64, message = "must be at most 64 characters")
        @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "must contain only letters, digits, hyphen and underscore")
        String code,
        @NotBlank(message = "must not be blank")
        @Size(max = 64, message = "must be at most 64 characters")
        String name) {
}
