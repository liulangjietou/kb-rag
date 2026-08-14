package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.domain.entity.Tenant;

/**
 * Tenant row of the console list.
 *
 * @param tenantId tenant business id
 * @param code     stable code, immutable after creation
 * @param name     display name
 * @param status   {@code ENABLED} or {@code DISABLED}
 * @param builtin  {@code true} for the default tenant shipped with the product
 * @param monthlyTokenQuota monthly model Token quota, zero means unlimited
 * @param createdAt ISO creation timestamp
 *
 * @author owlzhangfq@gmail.com
 */
public record TenantResponse(
        @JsonProperty("tenant_id") String tenantId,
        String code,
        String name,
        String status,
        boolean builtin,
        @JsonProperty("monthly_token_quota") long monthlyTokenQuota,
        @JsonProperty("created_at") String createdAt) {

    /**
     * Maps one tenant onto the transport shape.
     *
     * @param tenant tenant record
     * @return tenant row
     */
    public static TenantResponse from(Tenant tenant) {
        return new TenantResponse(
                tenant.getTenantId(),
                tenant.getCode(),
                tenant.getName(),
                tenant.getStatus() == null ? null : tenant.getStatus().name(),
                tenant.builtin(),
                tenant.getMonthlyTokenQuota() == null ? 0L : tenant.getMonthlyTokenQuota(),
                tenant.getCreatedAt() == null ? null : tenant.getCreatedAt().toString());
    }
}
