package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.domain.model.AlertConfig;

/**
 * Alert settings view.
 *
 * @param enabled              master switch of the dispatcher
 * @param webhookUrl           incoming webhook URL, blank degrades every alert to an error log
 * @param taskFailThreshold    consecutive failures of one task type that raise an alert
 * @param degradeRateThreshold share of degraded retrieval calls that raises an alert
 * @param syncBacklogThreshold number of chunks waiting for an engine that raises an alert
 * @param silenceMinutes       minutes during which the same category is not sent again
 *
 * @author owlzhangfq@gmail.com
 */
public record AlertConfigResponse(
        boolean enabled,
        @JsonProperty("webhook_url") String webhookUrl,
        @JsonProperty("task_fail_threshold") int taskFailThreshold,
        @JsonProperty("degrade_rate_threshold") double degradeRateThreshold,
        @JsonProperty("sync_backlog_threshold") int syncBacklogThreshold,
        @JsonProperty("silence_minutes") int silenceMinutes) {

    /**
     * Maps a domain snapshot onto the transport shape.
     *
     * @param config domain settings
     * @return transport response
     */
    public static AlertConfigResponse from(AlertConfig config) {
        return new AlertConfigResponse(config.isEnabled(), config.getWebhookUrl(),
                config.getTaskFailThreshold(), config.getDegradeRateThreshold(),
                config.getSyncBacklogThreshold(), config.getSilenceMinutes());
    }
}
