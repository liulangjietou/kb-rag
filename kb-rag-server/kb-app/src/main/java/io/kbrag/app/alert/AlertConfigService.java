package io.kbrag.app.alert;

import io.kbrag.app.system.SystemConfigService;
import io.kbrag.domain.model.AlertConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads and writes the alert settings held in {@code t_kb_system_config}.
 *
 * <p>Each field is its own row, following the keys the contract fixes. A missing row falls back to the
 * documented default rather than disabling the feature silently, so a partially written configuration
 * still behaves predictably.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertConfigService {

    /** Configuration key of the master switch. */
    public static final String KEY_ENABLED = "alert.enabled";

    /** Configuration key of the webhook URL. */
    public static final String KEY_WEBHOOK_URL = "alert.webhook_url";

    /** Configuration key of the consecutive task failure threshold. */
    public static final String KEY_TASK_FAIL_THRESHOLD = "alert.task_fail_threshold";

    /** Configuration key of the retrieval degradation ratio threshold. */
    public static final String KEY_DEGRADE_RATE_THRESHOLD = "alert.degrade_rate_threshold";

    /** Configuration key of the double write backlog threshold. */
    public static final String KEY_SYNC_BACKLOG_THRESHOLD = "alert.sync_backlog_threshold";

    /** Configuration key of the silence window. */
    public static final String KEY_SILENCE_MINUTES = "alert.silence_minutes";

    private static final String DESCRIPTION = "operations alert dispatcher settings";

    private final SystemConfigService systemConfigService;

    /**
     * Loads the current settings.
     *
     * @return settings, defaults for every key that was never written
     */
    public AlertConfig current() {
        AlertConfig defaults = AlertConfig.defaults();
        AlertConfig config = new AlertConfig();
        config.setEnabled(readBoolean(KEY_ENABLED, defaults.isEnabled()));
        config.setWebhookUrl(systemConfigService.get(KEY_WEBHOOK_URL));
        config.setTaskFailThreshold(readInt(KEY_TASK_FAIL_THRESHOLD, defaults.getTaskFailThreshold()));
        config.setDegradeRateThreshold(
                readDouble(KEY_DEGRADE_RATE_THRESHOLD, defaults.getDegradeRateThreshold()));
        config.setSyncBacklogThreshold(
                readInt(KEY_SYNC_BACKLOG_THRESHOLD, defaults.getSyncBacklogThreshold()));
        config.setSilenceMinutes(readInt(KEY_SILENCE_MINUTES, defaults.getSilenceMinutes()));
        return config;
    }

    /**
     * Replaces the settings.
     *
     * @param config new settings
     * @return stored settings
     */
    public AlertConfig update(AlertConfig config) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(KEY_ENABLED, String.valueOf(config.isEnabled()));
        values.put(KEY_WEBHOOK_URL, config.getWebhookUrl() == null ? "" : config.getWebhookUrl().trim());
        values.put(KEY_TASK_FAIL_THRESHOLD, String.valueOf(config.getTaskFailThreshold()));
        values.put(KEY_DEGRADE_RATE_THRESHOLD, String.valueOf(config.getDegradeRateThreshold()));
        values.put(KEY_SYNC_BACKLOG_THRESHOLD, String.valueOf(config.getSyncBacklogThreshold()));
        values.put(KEY_SILENCE_MINUTES, String.valueOf(config.getSilenceMinutes()));
        systemConfigService.putAll(values, DESCRIPTION);
        log.info("alert configuration updated, enabled={}, webhookConfigured={}, silenceMinutes={}",
                config.isEnabled(), config.getWebhookUrl() != null && !config.getWebhookUrl().isBlank(),
                config.getSilenceMinutes());
        return current();
    }

    private boolean readBoolean(String key, boolean fallback) {
        String value = systemConfigService.get(key);
        return value == null || value.isBlank() ? fallback : Boolean.parseBoolean(value.trim());
    }

    private int readInt(String key, int fallback) {
        String value = systemConfigService.get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            log.info("unusable alert threshold, key={}, value={}", key, value);
            return fallback;
        }
    }

    private double readDouble(String key, double fallback) {
        String value = systemConfigService.get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            log.info("unusable alert threshold, key={}, value={}", key, value);
            return fallback;
        }
    }
}
