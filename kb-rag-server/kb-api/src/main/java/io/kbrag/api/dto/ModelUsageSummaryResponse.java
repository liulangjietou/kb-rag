package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.app.modelusage.ModelUsageSummary;

import java.util.List;

/**
 * Tenant monthly model quota and cost response.
 *
 * @author owlzhangfq@gmail.com
 */
public record ModelUsageSummaryResponse(
        @JsonProperty("tenant_id") String tenantId,
        String month,
        @JsonProperty("quota_tokens") long quotaTokens,
        @JsonProperty("used_tokens") long usedTokens,
        @JsonProperty("reserved_tokens") long reservedTokens,
        @JsonProperty("remaining_tokens") Long remainingTokens,
        @JsonProperty("estimated_calls") long estimatedCalls,
        @JsonProperty("unpriced_calls") long unpricedCalls,
        List<Cost> costs) {

    public static ModelUsageSummaryResponse from(ModelUsageSummary summary) {
        return new ModelUsageSummaryResponse(summary.tenantId(), summary.month(), summary.quotaTokens(),
                summary.usedTokens(), summary.reservedTokens(), summary.remainingTokens(),
                summary.estimatedCalls(), summary.unpricedCalls(), summary.costs().stream()
                .map(cost -> new Cost(cost.currency(), cost.costMicros())).toList());
    }

    /** Cost subtotal; different currencies stay in separate rows. */
    public record Cost(String currency, @JsonProperty("cost_micros") long costMicros) {
    }
}
