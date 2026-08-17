package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Tenant monthly model Token quota update.
 *
 * @param monthlyTokenQuota non-negative quota, zero means unlimited
 *
 * @author owlzhangfq@gmail.com
 */
public record UpdateTenantModelQuotaRequest(
        @JsonProperty("monthly_token_quota")
        @NotNull(message = "must not be null")
        @Min(value = 0, message = "must be at least 0")
        Long monthlyTokenQuota) {
}
