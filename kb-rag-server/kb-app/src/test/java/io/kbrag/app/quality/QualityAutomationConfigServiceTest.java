package io.kbrag.app.quality;

import io.kbrag.app.system.SystemConfigService;
import io.kbrag.domain.model.QualityAutomationConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 系统配置键与默认值、持久化往返。 */
class QualityAutomationConfigServiceTest {

    private final SystemConfigService systemConfig = mock(SystemConfigService.class);
    private final QualityAutomationConfigService service = new QualityAutomationConfigService(systemConfig);

    @Test
    void shouldDefaultAutoIssueOffWhenKeyMissing() {
        when(systemConfig.get(QualityAutomationConfigService.KEY_AUTO_ISSUE)).thenReturn(null);
        assertFalse(service.current().isAutoIssueFromEmployeeBad());
        assertFalse(service.autoIssueFromEmployeeBadEnabled());
    }

    @Test
    void shouldParseStoredBoolean() {
        when(systemConfig.get(QualityAutomationConfigService.KEY_AUTO_ISSUE)).thenReturn("true");
        assertTrue(service.current().isAutoIssueFromEmployeeBad());
    }

    @Test
    void shouldTreatNonBooleanStoredValueAsFalse() {
        when(systemConfig.get(QualityAutomationConfigService.KEY_AUTO_ISSUE)).thenReturn("not-a-flag");
        assertFalse(service.current().isAutoIssueFromEmployeeBad());
    }

    @Test
    void shouldPersistAndReReadAfterUpdate() {
        QualityAutomationConfig incoming = new QualityAutomationConfig();
        incoming.setAutoIssueFromEmployeeBad(true);
        doAnswer(invocation -> {
            when(systemConfig.get(QualityAutomationConfigService.KEY_AUTO_ISSUE)).thenReturn("true");
            return null;
        }).when(systemConfig).putAll(anyMap(), anyString());

        QualityAutomationConfig saved = service.update(incoming);

        verify(systemConfig).putAll(
                eq(Map.of(QualityAutomationConfigService.KEY_AUTO_ISSUE, "true")),
                eq("quality automation switches"));
        assertTrue(saved.isAutoIssueFromEmployeeBad());
    }
}