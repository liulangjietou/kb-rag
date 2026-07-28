package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.app.insight.SearchInsightService;

import java.util.List;

/**
 * Content gap report of one knowledge base, the M10 contract section 2.2.
 *
 * @param total             recorded retrievals in the window
 * @param zeroHitCount      retrievals that returned nothing
 * @param zeroHitRate       {@code zeroHitCount / total}, 0 when the window is empty
 * @param degradedCount     retrievals that carried at least one degradation marker
 * @param topZeroHitQueries most frequent zero hit query groups, largest first
 *
 * @author owlzhangfq@gmail.com
 */
public record SearchInsightStatsResponse(
        long total,
        @JsonProperty("zero_hit_count") long zeroHitCount,
        @JsonProperty("zero_hit_rate") double zeroHitRate,
        @JsonProperty("degraded_count") long degradedCount,
        @JsonProperty("top_zero_hit_queries") List<TopZeroHitQueryResponse> topZeroHitQueries) {

    /**
     * Maps the aggregated report onto the transport shape.
     *
     * @param stats aggregated report
     * @return response
     */
    public static SearchInsightStatsResponse from(SearchInsightService.InsightStats stats) {
        return new SearchInsightStatsResponse(
                stats.total(),
                stats.zeroHitCount(),
                stats.zeroHitRate(),
                stats.degradedCount(),
                stats.topZeroHitQueries().stream().map(TopZeroHitQueryResponse::from).toList());
    }

    /**
     * One zero hit query group of the report.
     *
     * @param queryDigest masked digest of the newest row of the group
     * @param count       zero hit rows in the group
     * @param lastAt      ISO time of the newest row of the group
     */
    public record TopZeroHitQueryResponse(
            @JsonProperty("query_digest") String queryDigest,
            long count,
            @JsonProperty("last_at") String lastAt) {

        private static TopZeroHitQueryResponse from(SearchInsightService.TopZeroHitQuery group) {
            return new TopZeroHitQueryResponse(
                    group.queryDigest(),
                    group.count(),
                    group.lastAt() == null ? null : group.lastAt().toString());
        }
    }
}
