package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.domain.model.QualityAutomationConfig;
import jakarta.validation.constraints.NotNull;

public record UpdateQualityAutomationConfigRequest(
        @JsonProperty("auto_issue_from_employee_bad") @NotNull Boolean autoIssueFromEmployeeBad) {

    public QualityAutomationConfig toConfig() {
        QualityAutomationConfig config = new QualityAutomationConfig();
        config.setAutoIssueFromEmployeeBad(autoIssueFromEmployeeBad);
        return config;
    }
}