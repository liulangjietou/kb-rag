package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.domain.entity.OperationAudit;

/**
 * One row of the console's operation audit view.
 *
 * @param auditId    business identifier
 * @param userId     operator user business id
 * @param username   operator login name, readable even after the account is gone
 * @param module     module the operation belongs to
 * @param action     action performed
 * @param targetType kind of object acted on
 * @param targetId   business id of the object acted on, {@code null} for batch operations
 * @param detail     JSON of business ids and summary fields
 * @param clientIp   source address of the request
 * @param requestId  correlation id, links the row to logs
 * @param createdAt  ISO operation timestamp
 *
 * @author owlzhangfq@gmail.com
 */
public record OperationAuditResponse(
        @JsonProperty("audit_id") String auditId,
        @JsonProperty("user_id") String userId,
        String username,
        String module,
        String action,
        @JsonProperty("target_type") String targetType,
        @JsonProperty("target_id") String targetId,
        String detail,
        @JsonProperty("client_ip") String clientIp,
        @JsonProperty("request_id") String requestId,
        @JsonProperty("created_at") String createdAt) {

    /**
     * Maps a stored audit row onto its response.
     *
     * @param row stored row
     * @return response
     */
    public static OperationAuditResponse from(OperationAudit row) {
        return new OperationAuditResponse(
                row.getAuditId(),
                row.getUserId(),
                row.getUsername(),
                row.getModule(),
                row.getAction(),
                row.getTargetType(),
                row.getTargetId(),
                row.getDetail(),
                row.getClientIp(),
                row.getRequestId(),
                row.getCreatedAt() == null ? null : row.getCreatedAt().toString());
    }
}
