package io.kbrag.infrastructure.notify;

import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.port.WebhookNotifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Posts alerts to an incoming webhook.
 *
 * <p>The body is the plain text envelope {@code {"msgtype":"text","text":{"content":"..."}}}, which
 * DingTalk, WeCom and Slack all accept. Picking the lowest common denominator is what lets an operator
 * paste any of the three URLs without the service needing to know which platform is behind it.
 *
 * <p>The whole target URL comes from the runtime configuration, so a call is only ever issued to the
 * address an authenticated operator entered on purpose.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
public class HttpWebhookNotifier implements WebhookNotifier {

    private static final String FIELD_MSG_TYPE = "msgtype";
    private static final String FIELD_TEXT = "text";
    private static final String FIELD_CONTENT = "content";
    private static final String MSG_TYPE_TEXT = "text";

    private final RestClient restClient;

    public HttpWebhookNotifier(KbProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofMillis(properties.getAlert().getWebhookTimeoutMs());
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public void notify(String webhookUrl, String content) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(FIELD_MSG_TYPE, MSG_TYPE_TEXT);
        payload.put(FIELD_TEXT, Map.of(FIELD_CONTENT, content));
        try {
            restClient.post()
                    .uri(webhookUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(JsonUtil.toJson(payload))
                    .retrieve()
                    .toBodilessEntity();
            log.info("webhook alert delivered, contentLength={}", content.length());
        } catch (Exception e) {
            log.error("webhook alert delivery failed, errorCode={}", ErrorCode.INTERNAL_ERROR, e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "webhook delivery failed", e);
        }
    }
}
