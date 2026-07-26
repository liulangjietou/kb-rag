package io.kbrag.domain.port;

/**
 * Outbound port of the operations webhook.
 *
 * <p>Kept as a port so the alert policy — thresholds, silence window, degradation to a log line — can be
 * unit tested without a HTTP server, and so a deployment can point at DingTalk, WeCom or Slack by
 * changing a URL rather than an implementation.
 *
 * @author owlzhangfq@gmail.com
 */
public interface WebhookNotifier {

    /**
     * Posts a plain text message.
     *
     * @param webhookUrl target URL
     * @param content    message body
     */
    void notify(String webhookUrl, String content);
}
