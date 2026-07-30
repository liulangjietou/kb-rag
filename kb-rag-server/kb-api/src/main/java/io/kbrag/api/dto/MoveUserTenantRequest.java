package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * Target tenant of an account move.
 *
 * @param tenantId tenant business id the account moves to
 *
 * @author owlzhangfq@gmail.com
 */
public record MoveUserTenantRequest(
        @JsonProperty("tenant_id")
        @NotBlank(message = "must not be blank") String tenantId) {
}
