package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.domain.entity.QualityIssueRecord;

/** 经过问题内容授权后展示的处理记录，不暴露数据库内部主键。 */
public record QualityIssueRecordResponse(String action, String note,
        @JsonProperty("actor_name") String actorName, @JsonProperty("case_id") String caseId,
        @JsonProperty("run_id") String runId, @JsonProperty("created_at") String createdAt) {
    /** 将追加记录映射到界面所需字段。 */
    public static QualityIssueRecordResponse from(QualityIssueRecord record) {
        return new QualityIssueRecordResponse(record.getAction().name(), record.getNote(), record.getActorName(),
                record.getCaseId(), record.getRunId(), record.getCreatedAt() == null ? null : record.getCreatedAt().toString());
    }
}
