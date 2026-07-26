package io.kbrag.infrastructure.provider.chat;

import com.fasterxml.jackson.databind.JsonNode;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.ProviderErrorType;
import io.kbrag.common.exception.ProviderException;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.model.ChatMessage;
import io.kbrag.domain.model.HealthStatus;
import io.kbrag.domain.port.ChatProvider;
import io.kbrag.infrastructure.provider.DashScopeHttp;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DashScope chat provider driven by plain HTTP.
 *
 * <p>Transport choice: the OpenAI compatible endpoint {@code {baseUrl}/chat/completions} is used
 * rather than the native DashScope path, so the same implementation serves DashScope, Azure OpenAI,
 * Ollama and vLLM by changing {@code kb.chat.base-url}. No vendor SDK is involved.
 *
 * <p>The system instruction is always prepended as the single system message and the caller supplied
 * turns keep their own roles; a caller cannot inject a second system message because
 * {@link ChatMessage} only ever leaves the rewrite stage with a user or assistant role.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
public class DashScopeChatProvider implements ChatProvider {

    /** Provider name reported by the model status endpoint. */
    public static final String PROVIDER_NAME = "dashscope";

    private static final String STAGE = "chat";
    private static final String COMPLETIONS_PATH = "/chat/completions";
    private static final String FIELD_MODEL = "model";
    private static final String FIELD_MESSAGES = "messages";
    private static final String FIELD_ROLE = "role";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_TEMPERATURE = "temperature";
    private static final String FIELD_MAX_TOKENS = "max_tokens";
    private static final String FIELD_CHOICES = "choices";
    private static final String FIELD_MESSAGE = "message";
    private static final String HEALTH_PROBE_TEXT = "ping";

    private final KbProperties.Chat config;
    private final RestClient restClient;

    /**
     * Builds a chat provider from one chat configuration.
     *
     * <p>Takes the sub-configuration directly, not the whole {@link KbProperties} tree, which is what
     * lets a second bean serve the LLM-as-judge stage with an independent model (requirement section
     * 4.6 "judge model configured independently") while sharing the same credential and transport as
     * the query rewrite one - only {@code model} ever needs to differ between the two instances.
     *
     * @param config chat sub-configuration
     */
    public DashScopeChatProvider(KbProperties.Chat config) {
        this.config = config;
        this.restClient = DashScopeHttp.client(config.getBaseUrl(), config.getApiKey(), config.getTimeoutMs());
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public String model() {
        return config.getModel();
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public String complete(String systemPrompt, List<ChatMessage> messages) {
        if (CollectionUtils.isEmpty(messages)) {
            throw new ProviderException(PROVIDER_NAME, ProviderErrorType.UNKNOWN,
                    "chat requires at least one message");
        }
        List<Map<String, Object>> payloadMessages = new ArrayList<>(messages.size() + 1);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            payloadMessages.add(message(ChatMessage.ROLE_SYSTEM, systemPrompt));
        }
        for (ChatMessage message : messages) {
            payloadMessages.add(message(message.getRole(), message.getContent()));
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(FIELD_MODEL, config.getModel());
        payload.put(FIELD_MESSAGES, payloadMessages);
        payload.put(FIELD_TEMPERATURE, config.getTemperature());
        payload.put(FIELD_MAX_TOKENS, config.getMaxTokens());

        String body = DashScopeHttp.post(restClient, COMPLETIONS_PATH, payload, PROVIDER_NAME, STAGE);
        return parseContent(body);
    }

    @Override
    public HealthStatus healthCheck() {
        try {
            complete(null, List.of(ChatMessage.user(HEALTH_PROBE_TEXT)));
            return HealthStatus.up(config.getModel());
        } catch (ProviderException e) {
            log.error("chat health check failed, errorCode={}, type={}",
                    e.getErrorCode(), e.getErrorType(), e);
            return HealthStatus.down(e.getErrorType().name());
        }
    }

    private Map<String, Object> message(String role, String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put(FIELD_ROLE, role);
        message.put(FIELD_CONTENT, content);
        return message;
    }

    private String parseContent(String body) {
        JsonNode root = JsonUtil.parse(body, JsonNode.class);
        JsonNode choices = root == null ? null : root.path(FIELD_CHOICES);
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            log.error("chat response carries no choice, errorCode={}", ErrorCode.UPSTREAM_MODEL_ERROR);
            throw new ProviderException(PROVIDER_NAME, ProviderErrorType.UNKNOWN,
                    "chat response carries no choice");
        }
        return choices.get(0).path(FIELD_MESSAGE).path(FIELD_CONTENT).asText("");
    }
}
