package io.kbrag.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Operations alert settings, layer two of the configuration model: stored in
 * {@code t_kb_system_config} and editable while the service runs.
 *
 * <p>A blank webhook URL is a supported state, not a misconfiguration: the alerts then degrade to error
 * logs, which is what a deployment without a chat platform needs.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AlertConfig {

    /** Master switch of the alert dispatcher. */
    @JsonProperty("enabled")
    private boolean enabled;

    /** Incoming webhook URL of DingTalk, WeCom or Slack; blank degrades every alert to an error log. */
    @JsonProperty("webhook_url")
    private String webhookUrl;

    /** Consecutive failures of one task type that raise an alert. */
    @JsonProperty("task_fail_threshold")
    private int taskFailThreshold = 3;

    /** Share of degraded retrieval calls over the observation window that raises an alert. */
    @JsonProperty("degrade_rate_threshold")
    private double degradeRateThreshold = 0.3d;

    /** Number of chunks waiting to reach a search engine that raises an alert. */
    @JsonProperty("sync_backlog_threshold")
    private int syncBacklogThreshold = 1000;

    /** Minutes during which the same alert category is not sent again. */
    @JsonProperty("silence_minutes")
    private int silenceMinutes = 30;

    /**
     * Settings of a deployment that never configured alerts.
     *
     * @return disabled configuration with the documented thresholds
     */
    public static AlertConfig defaults() {
        return new AlertConfig();
    }

    /**
     * Tells whether an alert can actually be delivered.
     *
     * @return {@code true} when the dispatcher is on and a target URL is set
     */
    public boolean deliverable() {
        return enabled && webhookUrl != null && !webhookUrl.isBlank();
    }
}
