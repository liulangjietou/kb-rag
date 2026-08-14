package io.kbrag.infrastructure.provider.chat;

import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.port.ChatProvider;
import io.kbrag.domain.port.ChatProviderFactory;
import io.kbrag.domain.port.ModelCallMeter;
import io.kbrag.infrastructure.provider.UnconfiguredChatProvider;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a chat provider per model name, sharing the deployment credential and transport.
 *
 * <p>Instances are cached per model because a provider holds an HTTP client: building one per request would
 * create a connection pool per call. The key space is bounded by the number of models the released
 * application versions name, which is a small, operator controlled set.
 *
 * <p>The generation budget is {@code kb.chat.answer-max-tokens} rather than {@code kb.chat.max-tokens}: the
 * latter sizes a query rewrite, which is one short line, and reusing it here would truncate every answer
 * mid sentence.
 *
 * <p>In zero key mode every model resolves to the unconfigured placeholder, so the chat endpoint fails with
 * the explicit upstream error instead of pretending a model exists.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
public class ModelChatProviderFactory implements ChatProviderFactory {

    private final KbProperties properties;
    private final ModelCallMeter modelCallMeter;
    private final Map<String, ChatProvider> byModel = new ConcurrentHashMap<>();

    /** Backward-compatible constructor for direct unit tests. */
    public ModelChatProviderFactory(KbProperties properties) {
        this(properties, ModelCallMeter.NOOP);
    }

    /** Builds the production factory with the shared durable meter. */
    public ModelChatProviderFactory(KbProperties properties, ModelCallMeter modelCallMeter) {
        this.properties = properties;
        this.modelCallMeter = modelCallMeter;
    }

    @Override
    public ChatProvider forModel(String model) {
        KbProperties.Chat config = properties.getChat();
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            return new UnconfiguredChatProvider();
        }
        String effectiveModel = model == null || model.isBlank() ? config.getModel() : model.trim();
        return byModel.computeIfAbsent(effectiveModel, name -> {
            log.info("chat provider instantiated for a version pinned model, model={}", name);
            return new DashScopeChatProvider(withModel(config, name), modelCallMeter);
        });
    }

    /**
     * Copies a chat configuration substituting only the model.
     *
     * @param source deployment chat configuration
     * @param model  model the version snapshot froze
     * @return copy pointing at that model
     */
    private KbProperties.Chat withModel(KbProperties.Chat source, String model) {
        KbProperties.Chat copy = new KbProperties.Chat();
        copy.setProvider(source.getProvider());
        copy.setModel(model);
        copy.setApiKey(source.getApiKey());
        copy.setBaseUrl(source.getBaseUrl());
        copy.setTimeoutMs(source.getTimeoutMs());
        copy.setTemperature(source.getTemperature());
        copy.setMaxTokens(source.getAnswerMaxTokens());
        return copy;
    }
}
