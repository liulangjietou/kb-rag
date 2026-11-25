package io.kbrag.app.quality;

import io.kbrag.app.system.SystemConfigService;
import io.kbrag.domain.model.QualityAutomationConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class QualityAutomationConfigService {

    public static final String KEY_AUTO_ISSUE = "quality.auto_issue_employee_bad";

    private final SystemConfigService systemConfigService;

    public QualityAutomationConfig current() {
        QualityAutomationConfig defaults = QualityAutomationConfig.defaults();
        QualityAutomationConfig config = new QualityAutomationConfig();
        String stored = systemConfigService.get(KEY_AUTO_ISSUE);
        config.setAutoIssueFromEmployeeBad(stored == null
                ? defaults.isAutoIssueFromEmployeeBad()
                : Boolean.parseBoolean(stored));
        return config;
    }

    public QualityAutomationConfig update(QualityAutomationConfig config) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(KEY_AUTO_ISSUE, Boolean.toString(config.isAutoIssueFromEmployeeBad()));
        systemConfigService.putAll(values, "quality automation switches");
        return current();
    }

    public boolean autoIssueFromEmployeeBadEnabled() {
        return current().isAutoIssueFromEmployeeBad();
    }
}