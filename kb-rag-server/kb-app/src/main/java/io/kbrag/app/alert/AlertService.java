package io.kbrag.app.alert;

import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.enums.AlertType;
import io.kbrag.domain.model.AlertConfig;
import io.kbrag.domain.port.WebhookNotifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

/**
 * Dispatches operations alerts, applying the silence window and the delivery fallback.
 *
 * <p><b>Why a silence window at all.</b> The three triggers observe conditions that persist: a failing
 * task type keeps failing, a growing backlog keeps growing. Without a per category cooldown the first
 * incident would produce a message every evaluation pass, and the channel that is supposed to carry the
 * warning becomes the reason nobody reads it.
 *
 * <p><b>An unreachable channel is not an incident.</b> A blank URL, a disabled dispatcher and a failed
 * post all degrade to an error log. The alert is a notification about a problem; letting it raise a
 * second problem in the caller would turn a monitoring gap into an outage.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertService {

    private static final String MESSAGE_TEMPLATE = "[kb-rag][%s] %s";

    private final AlertConfigService alertConfigService;
    private final WebhookNotifier webhookNotifier;

    /** Last delivery per category, the state behind the silence window. */
    private final Map<AlertType, Instant> lastSentAt = new EnumMap<>(AlertType.class);

    /**
     * Raises an alert unless its category is still silenced.
     *
     * @param type    alert category
     * @param content message body
     * @return {@code true} when the message reached the webhook
     */
    public synchronized boolean raise(AlertType type, String content) {
        AlertConfig config = alertConfigService.current();
        if (isSilenced(type, config, Instant.now())) {
            log.info("alert suppressed by the silence window, type={}, silenceMinutes={}",
                    type.code(), config.getSilenceMinutes());
            return false;
        }
        boolean delivered = deliver(type, content, config);
        if (delivered) {
            lastSentAt.put(type, Instant.now());
        }
        return delivered;
    }

    /**
     * Sends a message ignoring the silence window, used by the manual console probe.
     *
     * <p><b>This path fails fast where a real alert degrades.</b> An automatic alert with no webhook is a
     * deployment that chose not to configure one, and turning that into an exception would break indexing.
     * A human clicking "send test" is asking whether the wiring works, so answering with a silent success
     * would be a wrong answer; the missing URL is reported as an invalid request instead.
     *
     * @param content message body
     * @return {@code true} when the message reached the webhook
     */
    public boolean sendTest(String content) {
        AlertConfig config = alertConfigService.current();
        if (config.getWebhookUrl() == null || config.getWebhookUrl().isBlank()) {
            throw BizException.invalidParam("尚未配置告警 webhook URL");
        }
        // The master switch is bypassed as well: an operator verifies the wiring before turning alerts on,
        // and refusing the probe until the feature is enabled would invert that order.
        return post(AlertType.TEST, content, config.getWebhookUrl());
    }

    /**
     * Tells whether a category is still inside its silence window.
     *
     * @param type   alert category
     * @param config current settings
     * @param now    evaluation instant
     * @return {@code true} when the category was notified recently enough
     */
    boolean isSilenced(AlertType type, AlertConfig config, Instant now) {
        Instant last = lastSentAt.get(type);
        if (last == null || config.getSilenceMinutes() <= 0) {
            return false;
        }
        return last.plus(Duration.ofMinutes(config.getSilenceMinutes())).isAfter(now);
    }

    /**
     * Performs the delivery, degrading to an error log when the channel is unavailable.
     *
     * @param type    alert category
     * @param content message body
     * @param config  current settings
     * @return {@code true} when the message reached the webhook
     */
    private boolean deliver(AlertType type, String content, AlertConfig config) {
        if (!config.deliverable()) {
            log.error("alert raised without a reachable webhook, errorCode={}, type={}, message={}",
                    ErrorCode.INTERNAL_ERROR, type.code(), String.format(MESSAGE_TEMPLATE, type.code(), content));
            return false;
        }
        return post(type, content, config.getWebhookUrl());
    }

    /**
     * Posts one message, turning a transport failure into an error log.
     *
     * @param type       alert category
     * @param content    message body
     * @param webhookUrl target URL
     * @return {@code true} when the webhook accepted the message
     */
    private boolean post(AlertType type, String content, String webhookUrl) {
        String message = String.format(MESSAGE_TEMPLATE, type.code(), content);
        try {
            webhookNotifier.notify(webhookUrl, message);
            log.info("alert delivered, type={}", type.code());
            return true;
        } catch (Exception e) {
            log.error("alert delivery failed, errorCode={}, type={}, message={}",
                    ErrorCode.INTERNAL_ERROR, type.code(), message, e);
            return false;
        }
    }
}
