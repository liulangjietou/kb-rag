package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.domain.model.QualityAutomationConfig;

public record QualityAutomationConfigResponse(
        @JsonProperty("auto_issue_from_employee_bad") boolean autoIssueFromEmployeeBad) {

    public static QualityAutomationConfigResponse from(QualityAutomationConfig config) {
        return new QualityAutomationConfigResponse(config.isAutoIssueFromEmployeeBad());
    }
}