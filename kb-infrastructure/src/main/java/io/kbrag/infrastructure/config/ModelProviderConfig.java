package io.kbrag.infrastructure.config;

import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.port.ChatProvider;
import io.kbrag.domain.port.EmbeddingProvider;
import io.kbrag.domain.port.RerankProvider;
import io.kbrag.domain.port.VisionProvider;
import io.kbrag.infrastructure.provider.UnconfiguredChatProvider;
import io.kbrag.infrastructure.provider.UnconfiguredRerankProvider;
import io.kbrag.infrastructure.provider.UnconfiguredVisionProvider;
import io.kbrag.infrastructure.provider.embedding.DashScopeEmbeddingProvider;
import io.kbrag.infrastructure.provider.embedding.UnconfiguredEmbeddingProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Single decision point of the zero key mode.
 *
 * <p>A blank credential yields the unconfigured provider, which makes the indexing pipeline skip
 * embedding and the retrieval service fall back to the BM25 single route. No other class has to
 * inspect the configuration, and the application always starts.
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
     * Placeholder rerank provider until M2 wires the rerank stage.
     *
     * @return unconfigured rerank provider
     */
    @Bean
    public RerankProvider rerankProvider() {
        return new UnconfiguredRerankProvider();
    }

    /**
     * Placeholder chat provider until M2 wires question answering.
     *
     * @return unconfigured chat provider
     */
    @Bean
    public ChatProvider chatProvider() {
        return new UnconfiguredChatProvider();
    }

    /**
     * Placeholder vision provider until M2 wires image understanding.
     *
     * @return unconfigured vision provider
     */
    @Bean
    public VisionProvider visionProvider() {
        return new UnconfiguredVisionProvider();
    }
}
