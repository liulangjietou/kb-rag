package io.kbrag.infrastructure.config;

import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.port.ChatProvider;
import io.kbrag.domain.port.EmbeddingProvider;
import io.kbrag.domain.port.RerankProvider;
import io.kbrag.domain.port.VisionProvider;
import io.kbrag.infrastructure.provider.UnconfiguredChatProvider;
import io.kbrag.infrastructure.provider.UnconfiguredRerankProvider;
import io.kbrag.infrastructure.provider.UnconfiguredVisionProvider;
import io.kbrag.infrastructure.provider.chat.DashScopeChatProvider;
import io.kbrag.infrastructure.provider.embedding.DashScopeEmbeddingProvider;
import io.kbrag.infrastructure.provider.rerank.DashScopeRerankProvider;
import io.kbrag.infrastructure.provider.embedding.UnconfiguredEmbeddingProvider;
import io.kbrag.infrastructure.provider.vision.DashScopeVisionProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Single decision point of the zero key mode.
 *
 * <p>A blank credential yields the unconfigured provider, which makes the indexing pipeline skip
 * embedding and the retrieval service fall back to the BM25 single route. No other class has to
 * inspect the configuration, and the application always starts.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Configuration
public class ModelProviderConfig {

    /**
     * Selects the embedding provider implementation.
     *
     * @param properties bound configuration
     * @return configured provider, or the unconfigured placeholder in zero key mode
     */
    @Bean
    public EmbeddingProvider embeddingProvider(KbProperties properties) {
        KbProperties.Embedding config = properties.getEmbedding();
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            log.info("embedding provider not configured, running in zero key mode, "
                    + "vector route disabled and embedding skipped");
            return new UnconfiguredEmbeddingProvider();
        }
        log.info("embedding provider configured, provider={}, model={}, dimension={}",
                config.getProvider(), config.getModel(), config.getDimension());
        return new DashScopeEmbeddingProvider(properties);
    }

    /**
     * Selects the rerank provider implementation.
     *
     * <p>The rerank credential is configured independently from the embedding one, so a deployment
     * can rerank a BM25 only recall; a blank key simply removes the stage from the pipeline.
     *
     * @param properties bound configuration
     * @return configured provider, or the unconfigured placeholder
     */
    @Bean
    public RerankProvider rerankProvider(KbProperties properties) {
        KbProperties.Rerank config = properties.getRerank();
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            log.info("rerank provider not configured, rerank stage disabled");
            return new UnconfiguredRerankProvider();
        }
        log.info("rerank provider configured, provider={}, model={}, timeoutMs={}",
                config.getProvider(), config.getModel(), config.getTimeoutMs());
        return new DashScopeRerankProvider(properties);
    }

    /**
     * Selects the chat provider implementation, consumed by the query rewrite stage and by the LLM
     * semantic split strategy.
     *
     * <p>{@code @Primary} since M4b: {@link #judgeChatProvider} joined the container as a second
     * {@link ChatProvider} bean, and every collaborator that autowires the type without naming a
     * specific bean has to keep resolving to this one.
     *
     * @param properties bound configuration
     * @return configured provider, or the unconfigured placeholder
     */
    @Primary
    @Bean
    public ChatProvider chatProvider(KbProperties properties) {
        KbProperties.Chat config = properties.getChat();
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            log.info("chat provider not configured, query rewrite disabled");
            return new UnconfiguredChatProvider();
        }
        log.info("chat provider configured, provider={}, model={}", config.getProvider(), config.getModel());
        return new DashScopeChatProvider(config);
    }

    /**
     * Selects the LLM-as-judge chat provider, requirement section 4.6 "judge model independent from
     * the generation model, to prevent a model from grading its own answer".
     *
     * <p>Shares the credential, base URL and timeout of {@code kb.chat} - a second provider account is
     * not the point, an independent grader model is - and only substitutes {@code kb.eval.judge-model}
     * when it is set; a blank value keeps grading on the same model {@link #chatProvider} uses.
     *
     * @param properties bound configuration
     * @return configured provider, or the unconfigured placeholder
     */
    @Bean
    public ChatProvider judgeChatProvider(KbProperties properties) {
        KbProperties.Chat chatConfig = properties.getChat();
        if (chatConfig.getApiKey() == null || chatConfig.getApiKey().isBlank()) {
            log.info("judge chat provider not configured, LLM-as-judge disabled");
            return new UnconfiguredChatProvider();
        }
        String judgeModel = properties.getEval().getJudgeModel();
        String effectiveModel = judgeModel == null || judgeModel.isBlank() ? chatConfig.getModel() : judgeModel;
        // Temperature 0 regardless of kb.chat.temperature, requirement section 4.6: a judge score has to
        // be reproducible across two runs of the same configuration, which a creative rewrite model need
        // not be.
        KbProperties.Chat judgeConfig = withModelAndZeroTemperature(chatConfig, effectiveModel);
        log.info("judge chat provider configured, model={}", judgeConfig.getModel());
        return new DashScopeChatProvider(judgeConfig);
    }

    /**
     * Copies a chat configuration substituting the model and forcing temperature to zero, so the judge
     * provider can point at a different, reproducible model without a second credential set.
     *
     * @param source configuration to copy
     * @param model  model name to substitute
     * @return copy carrying the substituted model and zero temperature
     */
    private KbProperties.Chat withModelAndZeroTemperature(KbProperties.Chat source, String model) {
        KbProperties.Chat copy = new KbProperties.Chat();
        copy.setProvider(source.getProvider());
        copy.setModel(model);
        copy.setApiKey(source.getApiKey());
        copy.setBaseUrl(source.getBaseUrl());
        copy.setTimeoutMs(source.getTimeoutMs());
        copy.setTemperature(0.0d);
        copy.setMaxTokens(source.getMaxTokens());
        return copy;
    }

    /**
     * Selects the vision provider implementation.
     *
     * <p>A blank key does not disable the image stage: images are still stored and registered, they
     * simply carry no textual proxy. That is what lets a deployment add a credential later and backfill
     * the proxies from the rows the stage left behind.
     *
     * @param properties bound configuration
     * @return configured provider, or the unconfigured placeholder
     */
    @Bean
    public VisionProvider visionProvider(KbProperties properties) {
        KbProperties.Vision config = properties.getVision();
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            log.info("vision provider not configured, image text proxies skipped");
            return new UnconfiguredVisionProvider();
        }
        log.info("vision provider configured, provider={}, model={}, timeoutMs={}",
                config.getProvider(), config.getModel(), config.getTimeoutMs());
        return new DashScopeVisionProvider(properties);
    }
}
