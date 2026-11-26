package io.kbrag.domain.model;

import lombok.Getter;
import lombok.Setter;

/**
 * Quality automation switches stored in {@code t_kb_system_config}.
 */
@Getter
@Setter
public class QualityAutomationConfig {

    /** When on, a BAD employee answer feedback creates knowledge quality issues per linked kb. */
    private boolean autoIssueFromEmployeeBad;

    public static QualityAutomationConfig defaults() {
        QualityAutomationConfig config = new QualityAutomationConfig();
        config.setAutoIssueFromEmployeeBad(false);
        return config;
    }
}