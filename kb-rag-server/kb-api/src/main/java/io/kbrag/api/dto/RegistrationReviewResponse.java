package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.domain.entity.RegistrationApplication;

import java.util.List;

/**
 * 管理员注册审核列表行，包含列表完成审核所需的全部信息。
 *
 * @author owlzhangfq@gmail.com
 */
public record RegistrationReviewResponse(
        @JsonProperty("application_id") String applicationId,
        String email,
        @JsonProperty("display_name") String displayName,
        @JsonProperty("team_name") String teamName,
        @JsonProperty("application_note") String applicationNote,
        String status,
        @JsonProperty("email_verified_at") String emailVerifiedAt,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("reviewed_at") String reviewedAt,
        @JsonProperty("tenant_id") String tenantId,
        @JsonProperty("role_ids") List<String> roleIds,
        @JsonProperty("rejection_reason") String rejectionReason) {

    public static RegistrationReviewResponse from(RegistrationApplication application,
                                                   List<String> roleIds) {
        return new RegistrationReviewResponse(
                application.getApplicationId(),
                application.getEmail(),
                application.getDisplayName(),
                application.getTeamName(),
                application.getApplicationNote(),
                application.getStatus() == null ? null : application.getStatus().name(),
                application.getEmailVerifiedAt() == null ? null : application.getEmailVerifiedAt().toString(),
                application.getCreatedAt() == null ? null : application.getCreatedAt().toString(),
                application.getReviewedAt() == null ? null : application.getReviewedAt().toString(),
                application.getApprovedTenantId(),
                roleIds == null ? List.of() : roleIds,
                application.getReviewReason());
    }
}
