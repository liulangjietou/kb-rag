package io.kbrag.app.modelusage;

import io.kbrag.domain.model.ModelCostTotal;

import java.util.List;

/**
 * One tenant's monthly quota and cost view.
 *
 * @param tenantId       tenant business id
 * @param month          YYYY-MM in Asia/Shanghai
 * @param quotaTokens    zero means unlimited
 * @param usedTokens     settled token count
 * @param reservedTokens calls currently in flight
 * @param remainingTokens null for unlimited, otherwise non-negative remainder
 * @param estimatedCalls successful calls charged from the conservative reservation
 * @param unpricedCalls  successful calls that had no active price configuration
 * @param costs          cost totals kept separate by currency
 *
 * @author owlzhangfq@gmail.com
 */
public record ModelUsageSummary(
        String tenantId,
        String month,
        long quotaTokens,
        long usedTokens,
        long reservedTokens,
        Long remainingTokens,
        long estimatedCalls,
        long unpricedCalls,
        List<ModelCostTotal> costs) {
}
