package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.app.openapi.ApiAuditService;

/**
 * Call volume statistics of one audit filter, requirement section 4.8.
 *
 * @param totalCalls    calls matching the filter
 * @param avgLatencyMs  mean server side duration
 * @param degradedCalls calls that carried at least one degradation marker
 * @param errorCalls    calls that were rejected with a business error code
 *
 * @author owlzhangfq@gmail.com
 */
public record ApiAuditStatsResponse(
        @JsonProperty("total_calls") long totalCalls,
        @JsonProperty("avg_latency_ms") double avgLatencyMs,
        @JsonProperty("degraded_calls") long degradedCalls,
        @JsonProperty("error_calls") long errorCalls) {

    /**
     * Maps the aggregated totals onto the transport shape.
     *
     * @param stats aggregated totals
     * @return response
     */
    public static ApiAuditStatsResponse from(ApiAuditService.AuditStats stats) {
        return new ApiAuditStatsResponse(stats.totalCalls(), stats.avgLatencyMs(),
                stats.degradedCalls(), stats.errorCalls());
    }
}
