package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.domain.entity.ModelUsage;

/**
 * Safe accounting dimensions of one model call; prompts, answers and exception text are absent.
 *
 * @author owlzhangfq@gmail.com
 */
public record ModelUsageRecordResponse(
        @JsonProperty("usage_id") String usageId,
        @JsonProperty("tenant_id") String tenantId,
        @JsonProperty("request_id") String requestId,
        String source,
        @JsonProperty("source_id") String sourceId,
        String provider,
        String capability,
        String model,
        String status,
        @JsonProperty("reserved_tokens") long reservedTokens,
        @JsonProperty("input_tokens") long inputTokens,
        @JsonProperty("output_tokens") long outputTokens,
        @JsonProperty("total_tokens") long totalTokens,
        boolean estimated,
        boolean priced,
        String currency,
        @JsonProperty("cost_micros") long costMicros,
        @JsonProperty("error_type") String errorType,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("completed_at") String completedAt) {

    public static ModelUsageRecordResponse from(ModelUsage usage) {
        return new ModelUsageRecordResponse(usage.getUsageId(), usage.getTenantId(), usage.getRequestId(),
                usage.getSource(), usage.getSourceId(), usage.getProvider(), usage.getCapability(),
                usage.getModel(), usage.getStatus(), valueOf(usage.getReservedTokens()),
                valueOf(usage.getInputTokens()), valueOf(usage.getOutputTokens()),
                valueOf(usage.getTotalTokens()), one(usage.getEstimated()), one(usage.getPriced()),
                usage.getCurrency(), valueOf(usage.getCostMicros()), usage.getErrorType(),
                usage.getCreatedAt() == null ? null : usage.getCreatedAt().toString(),
                usage.getCompletedAt() == null ? null : usage.getCompletedAt().toString());
    }

    private static boolean one(Integer value) {
        return value != null && value == 1;
    }

    private static long valueOf(Long value) {
        return value == null ? 0L : value;
    }
}
