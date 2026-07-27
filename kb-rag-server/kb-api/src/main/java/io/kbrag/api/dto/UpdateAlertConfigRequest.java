package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.model.AlertConfig;
import jakarta.validation.constraints.Min;

import java.util.Locale;

/**
 * New alert settings.
 *
 * <p>This is the fast-fail gate of the alert path: the thresholds are checked here so the dispatcher and the
 * evaluator can trust the values they read, and the webhook scheme is checked so a typo cannot turn into an
 * outbound call to an unexpected protocol.
 *
 * @param enabled              master switch of the dispatcher
 * @param webhookUrl           incoming webhook URL, blank degrades every alert to an error log
 * @param taskFailThreshold    consecutive failures of one task type that raise an alert
 * @param degradeRateThreshold share of degraded retrieval calls that raises an alert, between 0 and 1
 * @param syncBacklogThreshold number of chunks waiting for an engine that raises an alert
 * @param silenceMinutes       minutes during which the same category is not sent again
 *
 * @author owlzhangfq@gmail.com
 */
public record UpdateAlertConfigRequest(
        boolean enabled,
        @JsonProperty("webhook_url") String webhookUrl,
        @JsonProperty("task_fail_threshold") @Min(1) Integer taskFailThreshold,
        @JsonProperty("degrade_rate_threshold") Double degradeRateThreshold,
        @JsonProperty("sync_backlog_threshold") @Min(1) Integer syncBacklogThreshold,
        @JsonProperty("silence_minutes") @Min(0) Integer silenceMinutes) {

    private static final String SCHEME_HTTP = "http://";
    private static final String SCHEME_HTTPS = "https://";
    private static final double MIN_RATE = 0.0d;
    private static final double MAX_RATE = 1.0d;

    /**
     * Maps the payload onto the domain settings, keeping the stored value of every omitted field.
     *
     * @param current settings currently stored
     * @return validated settings
     */
    public AlertConfig toAlertConfig(AlertConfig current) {
        AlertConfig config = new AlertConfig();
        config.setEnabled(enabled);
        config.setWebhookUrl(normalizeUrl());
        config.setTaskFailThreshold(taskFailThreshold == null
                ? current.getTaskFailThreshold() : taskFailThreshold);
        config.setDegradeRateThreshold(degradeRateThreshold == null
                ? current.getDegradeRateThreshold() : degradeRateThreshold);
        config.setSyncBacklogThreshold(syncBacklogThreshold == null
                ? current.getSyncBacklogThreshold() : syncBacklogThreshold);
        config.setSilenceMinutes(silenceMinutes == null ? current.getSilenceMinutes() : silenceMinutes);
        if (config.getDegradeRateThreshold() < MIN_RATE || config.getDegradeRateThreshold() > MAX_RATE) {
            throw BizException.invalidParam("degrade_rate_threshold must be between 0 and 1");
        }
        if (enabled && (config.getWebhookUrl() == null || config.getWebhookUrl().isBlank())) {
            throw BizException.invalidParam("webhook_url is required to enable alerts");
        }
        return config;
    }

    private String normalizeUrl() {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return "";
        }
        String trimmed = webhookUrl.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (!lower.startsWith(SCHEME_HTTP) && !lower.startsWith(SCHEME_HTTPS)) {
            throw BizException.invalidParam("webhook_url must start with http:// or https://");
        }
        return trimmed;
    }
}
