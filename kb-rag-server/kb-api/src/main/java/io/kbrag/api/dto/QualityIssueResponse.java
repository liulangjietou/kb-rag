package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.app.auth.AccessGuard;
import io.kbrag.domain.entity.KnowledgeQualityIssue;
import io.kbrag.domain.entity.EvalCase;
import io.kbrag.domain.enums.QualityIssueSource;
import io.kbrag.domain.model.EvalCaseInput;

/** 明确投影字段，受限条目不携带来源、摘要、用例或应用关联。 */
public record QualityIssueResponse(
        @JsonProperty("issue_id") String issueId, @JsonProperty("kb_id") String kbId,
        @JsonProperty("source_type") String sourceType, @JsonProperty("source_id") String sourceId,
        String summary, String status, int revision,
        @JsonProperty("owner_user_id") String ownerUserId, @JsonProperty("owner_name") String ownerName,
        @JsonProperty("owned_by_me") boolean ownedByMe,
        String reason, @JsonProperty("dataset_id") String datasetId, @JsonProperty("case_id") String caseId,
        @JsonProperty("affected_app_version_id") String affectedAppVersionId,
        @JsonProperty("verified_run_id") String verifiedRunId,
        @JsonProperty("verified_app_version_id") String verifiedAppVersionId,
        @JsonProperty("content_restricted") boolean contentRestricted,
        @JsonProperty("created_at") String createdAt, @JsonProperty("resolved_at") String resolvedAt,
        @JsonProperty("case_revision") Integer caseRevision, EvalCaseResponse correction) {
    /** 详情才携带人工纠正内容，列表保持轻量。 */
    public static QualityIssueResponse from(KnowledgeQualityIssue issue, boolean readable, boolean detail) {
        return from(issue, readable, detail, null);
    }

    /** 详情返回当前用例及修订号，表单在冲突时需要由用户重新确认最新内容。 */
    public static QualityIssueResponse from(KnowledgeQualityIssue issue, boolean readable, boolean detail, EvalCase currentCase) {
        EvalCaseResponse correction = readable && detail && issue.getExpectedCaseInput() != null
                ? EvalCaseResponse.from(currentCase == null
                    ? JsonUtil.parse(issue.getExpectedCaseInput(), EvalCaseInput.class).toCase() : currentCase) : null;
        return new QualityIssueResponse(issue.getIssueId(), issue.getKbId(), issue.getSourceType().name(),
                readable && issue.getSourceType() == QualityIssueSource.BAD_FEEDBACK ? issue.getSourceId() : null,
                readable ? issue.getSummary() : null, issue.getStatus().name(),
                issue.getLockVersion(), issue.getOwnerUserId(), issue.getOwnerName(),
                AccessGuard.currentUserOrNull() != null && java.util.Objects.equals(issue.getOwnerUserId(), AccessGuard.currentUserOrNull().userId()),
                readable && issue.getReason() != null ? issue.getReason().name() : null,
                readable ? issue.getDatasetId() : null, readable ? issue.getCaseId() : null,
                readable ? issue.getAffectedAppVersionId() : null, readable ? issue.getVerifiedRunId() : null,
                readable ? issue.getVerifiedAppVersionId() : null, !readable,
                issue.getCreatedAt() == null ? null : issue.getCreatedAt().toString(),
                issue.getResolvedAt() == null ? null : issue.getResolvedAt().toString(),
                readable && currentCase != null ? currentCase.getLockVersion() : null, correction);
    }
}
