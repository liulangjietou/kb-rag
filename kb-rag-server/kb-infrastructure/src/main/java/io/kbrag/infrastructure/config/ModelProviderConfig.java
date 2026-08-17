package io.kbrag.infrastructure.config;

import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.port.ChatProvider;
import io.kbrag.domain.port.ChatProviderFactory;
import io.kbrag.domain.port.EmbeddingProvider;
import io.kbrag.domain.port.MultimodalEmbeddingProvider;
import io.kbrag.domain.port.ModelCallMeter;
import io.kbrag.domain.port.RerankProvider;
import io.kbrag.domain.port.VisionProvider;
import io.kbrag.infrastructure.provider.UnconfiguredChatProvider;
import io.kbrag.infrastructure.provider.UnconfiguredRerankProvider;
import io.kbrag.infrastructure.provider.UnconfiguredVisionProvider;
import io.kbrag.infrastructure.provider.chat.DashScopeChatProvider;
import io.kbrag.infrastructure.provider.chat.ModelChatProviderFactory;
import io.kbrag.infrastructure.provider.embedding.DashScopeEmbeddingProvider;
import io.kbrag.infrastructure.provider.embedding.DashScopeMultimodalEmbeddingProvider;
import io.kbrag.infrastructure.provider.embedding.NoopMultimodalEmbeddingProvider;
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
    public EmbeddingProvider embeddingProvider(KbProperties properties, ModelCallMeter modelCallMeter) {
        KbProperties.Embedding config = properties.getEmbedding();
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            log.info("embedding provider not configured, running in zero key mode, "
                    + "vector route disabled and embedding skipped");
            return new UnconfiguredEmbeddingProvider();
        }
        log.info("embedding provider configured, provider={}, model={}, dimension={}",
                config.getProvider(), config.getModel(), config.getDimension());
        return new DashScopeEmbeddingProvider(properties, modelCallMeter);
    }

    /**
     * Selects the multimodal embedding provider implementation, the M14 contract section 6.1.
     *
     * <p>Configured independently from the text embedding credential: the multimodal capability drives
     * a separate {@code _mm} index and a separate retrieval route, so a deployment can enable it on top
     * of an existing text embedding without either one implying the other. A blank key yields the noop
     * implementation, which makes the index pipeline skip the multimodal vectors and retrieval skip the
     * third route.
     *
     * @param properties bound configuration
     * @return configured provider, or the noop placeholder in zero key mode
     */
    @Bean
    public MultimodalEmbeddingProvider multimodalEmbeddingProvider(KbProperties properties,
                                                                   ModelCallMeter modelCallMeter) {
        KbProperties.MultimodalEmbedding config = properties.getMultimodalEmbedding();
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            log.info("multimodal embedding provider not configured, multimodal route disabled");
            return new NoopMultimodalEmbeddingProvider();
        }
        log.info("multimodal embedding provider configured, provider={}, model={}, dimension={}",
                config.getProvider(), config.getModel(), config.getDimension());
        return new DashScopeMultimodalEmbeddingProvider(properties, modelCallMeter);
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
    public RerankProvider rerankProvider(KbProperties properties, ModelCallMeter modelCallMeter) {
        KbProperties.Rerank config = properties.getRerank();
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            log.info("rerank provider not configured, rerank stage disabled");
            return new UnconfiguredRerankProvider();
        }
        log.info("rerank provider configured, provider={}, model={}, timeoutMs={}",
                config.getProvider(), config.getModel(), config.getTimeoutMs());
        return new DashScopeRerankProvider(properties, modelCallMeter);
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
    public ChatProvider chatProvider(KbProperties properties, ModelCallMeter modelCallMeter) {
        KbProperties.Chat config = properties.getChat();
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            log.info("chat provider not configured, query rewrite disabled");
            return new UnconfiguredChatProvider();
        }
        log.info("chat provider configured, provider={}, model={}", config.getProvider(), config.getModel());
        return new DashScopeChatProvider(config, modelCallMeter);
    }

    /**
     * Supplies the per model chat provider resolution the open chat endpoint needs, requirement section 4.7
     * "the generation model belongs to the application version snapshot".
     *
     * <p>A factory rather than another bean: the model is only known once a call resolved which application
     * version serves it, so the choice cannot be made at container startup.
     *
     * @param properties bound configuration
     * @return factory resolving providers by model name
     */
    @Bean
    public ChatProviderFactory chatProviderFactory(KbProperties properties, ModelCallMeter modelCallMeter) {
        return new ModelChatProviderFactory(properties, modelCallMeter);
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
    public ChatProvider judgeChatProvider(KbProperties properties, ModelCallMeter modelCallMeter) {
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
        return new DashScopeChatProvider(judgeConfig, modelCallMeter);
    }

    /**
     * Selects the graph extraction chat provider, requirement section 4.9.
     *
     * <p>Shares the credential, model and base URL of {@code kb.chat} and only substitutes the token
     * budget: {@code kb.chat.max-tokens} sizes a single line query rewrite, while one extraction answer
     * is a JSON object carrying every entity and relation of the passage. Running out mid object yields
     * truncated JSON, which the parser can only reject - the chunk is then counted as skipped and the
     * loss shows up as "output validation failed" although nothing was wrong with the model's answer.
     *
     * @param properties bound configuration
     * @return configured provider, or the unconfigured placeholder
     */
    @Bean
    public ChatProvider graphExtractionChatProvider(KbProperties properties, ModelCallMeter modelCallMeter) {
        KbProperties.Chat chatConfig = properties.getChat();
        if (chatConfig.getApiKey() == null || chatConfig.getApiKey().isBlank()) {
            log.info("graph extraction chat provider not configured, extraction disabled");
            return new UnconfiguredChatProvider();
        }
        KbProperties.Graph graphConfig = properties.getGraph();
        KbProperties.Chat extractConfig = withExtractionSettings(chatConfig,
                graphConfig.getExtractMaxTokens(), graphConfig.getExtractModel());
        log.info("graph extraction chat provider configured, model={}, maxTokens={}, inheritedModel={}",
                extractConfig.getModel(), extractConfig.getMaxTokens(),
                graphConfig.getExtractModel() == null || graphConfig.getExtractModel().isBlank());
        return new DashScopeChatProvider(extractConfig, modelCallMeter);
    }

    /**
     * Copies a chat configuration for the extraction stage, substituting the budget and the model.
     *
     * <p>The model is substituted only when the extraction one is set, so a deployment that says nothing
     * keeps running the shared chat model. Extraction and query rewrite want opposite things from a model
     * - rewrite is one sentence and wants fluency, extraction fills a fixed JSON shape and wants
     * throughput - and a turbo tier model roughly halves the generation time of the thousand-odd tokens
     * an extraction answer carries.
     *
     * @param source    configuration to copy
     * @param maxTokens generation budget to substitute
     * @param model     extraction model, blank inherits the source model
     * @return copy carrying the substituted budget and model
     */
    private KbProperties.Chat withExtractionSettings(KbProperties.Chat source, int maxTokens, String model) {
        KbProperties.Chat copy = new KbProperties.Chat();
        copy.setProvider(source.getProvider());
        copy.setModel(model == null || model.isBlank() ? source.getModel() : model.trim());
        copy.setApiKey(source.getApiKey());
        copy.setBaseUrl(source.getBaseUrl());
        copy.setTimeoutMs(source.getTimeoutMs());
        // The read ceiling has to come along: it is what bounds a generation, and an extraction answer is
        // the longest one the deployment produces. Leaving it at the field default silently ignored
        // whatever kb.chat.generate-timeout-ms the deployment had set.
        copy.setGenerateTimeoutMs(source.getGenerateTimeoutMs());
        copy.setTemperature(source.getTemperature());
        copy.setMaxTokens(maxTokens);
        return copy;
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
        copy.setGenerateTimeoutMs(source.getGenerateTimeoutMs());
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
    public VisionProvider visionProvider(KbProperties properties, ModelCallMeter modelCallMeter) {
        KbProperties.Vision config = properties.getVision();
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            log.info("vision provider not configured, image text proxies skipped");
            return new UnconfiguredVisionProvider();
        }
        log.info("vision provider configured, provider={}, model={}, timeoutMs={}",
                config.getProvider(), config.getModel(), config.getTimeoutMs());
        return new DashScopeVisionProvider(properties, modelCallMeter);
    }
}
