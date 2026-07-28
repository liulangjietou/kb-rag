package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.entity.SearchInsight;

import java.util.List;

/**
 * One insight row of the console's search insight view.
 *
 * @param insightId   business identifier
 * @param kbId        knowledge base the retrieval ran against
 * @param source      {@code CONSOLE} or {@code OPEN_API}
 * @param queryDigest masked and truncated query
 * @param resultCount nodes the call returned
 * @param topScore    score of the first node, {@code null} on zero hits
 * @param zeroHit     {@code true} when the call returned nothing
 * @param degraded    degradation markers of the call
 * @param requestId   correlation id
 * @param createdAt   ISO call timestamp
 *
 * @author owlzhangfq@gmail.com
 */
public record SearchInsightResponse(
        @JsonProperty("insight_id") String insightId,
        @JsonProperty("kb_id") String kbId,
        String source,
        @JsonProperty("query_digest") String queryDigest,
        @JsonProperty("result_count") Integer resultCount,
        @JsonProperty("top_score") Double topScore,
        @JsonProperty("zero_hit") Boolean zeroHit,
        List<String> degraded,
        @JsonProperty("request_id") String requestId,
        @JsonProperty("created_at") String createdAt) {

    /**
     * Maps a stored insight row onto its response.
     *
     * @param row stored row
     * @return response
     */
    public static SearchInsightResponse from(SearchInsight row) {
        return new SearchInsightResponse(
                row.getInsightId(),
                row.getKbId(),
                row.getSource() == null ? null : row.getSource().name(),
                row.getQueryDigest(),
                row.getResultCount(),
                row.getTopScore(),
                row.getZeroHit(),
                stringList(row.getDegraded()),
                row.getRequestId(),
                row.getCreatedAt() == null ? null : row.getCreatedAt().toString());
    }

    private static List<String> stringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        List<String> parsed = JsonUtil.parse(json, new TypeReference<List<String>>() {
        });
        return parsed == null ? List.of() : parsed;
    }
}
