package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.entity.ApiAuditLog;

import java.util.List;

/**
 * One audit row of the console's audit query view.
 *
 * @param auditLogId   business identifier
 * @param keyId        calling API key
 * @param appId        application called
 * @param appVersionId application version served, {@code null} when the call was rejected
 * @param targetStage  {@code release} or {@code beta}, {@code null} when the call was rejected
 * @param endpoint     {@code search} or {@code chat}
 * @param queryDigest  masked and truncated query
 * @param hitDocIds    document ids of the returned nodes
 * @param latencyMs    server side duration
 * @param degraded     degradation markers of the call
 * @param overrideKeys request level overrides applied
 * @param errorCode    business error code when the call was rejected
 * @param requestId    correlation id
 * @param createdAt    ISO call timestamp
 *
 * @author owlzhangfq@gmail.com
 */
public record ApiAuditLogResponse(
        @JsonProperty("audit_log_id") String auditLogId,
        @JsonProperty("key_id") String keyId,
        @JsonProperty("app_id") String appId,
        @JsonProperty("app_version_id") String appVersionId,
        @JsonProperty("target_stage") String targetStage,
        String endpoint,
        @JsonProperty("query_digest") String queryDigest,
        @JsonProperty("hit_doc_ids") List<String> hitDocIds,
        @JsonProperty("latency_ms") Integer latencyMs,
        List<String> degraded,
        @JsonProperty("override_keys") List<String> overrideKeys,
        @JsonProperty("error_code") String errorCode,
        @JsonProperty("request_id") String requestId,
        @JsonProperty("created_at") String createdAt) {

    /**
     * Maps a stored audit row onto its response.
     *
     * @param row stored row
     * @return response
     */
    public static ApiAuditLogResponse from(ApiAuditLog row) {
        return new ApiAuditLogResponse(
                row.getAuditLogId(),
                row.getKeyId(),
                row.getAppId(),
                row.getAppVersionId(),
                row.getTargetStage() == null ? null : row.getTargetStage().code(),
                row.getEndpoint(),
                row.getQueryDigest(),
                stringList(row.getHitDocIds()),
                row.getLatencyMs(),
                stringList(row.getDegraded()),
                stringList(row.getOverrideKeys()),
                row.getErrorCode(),
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
