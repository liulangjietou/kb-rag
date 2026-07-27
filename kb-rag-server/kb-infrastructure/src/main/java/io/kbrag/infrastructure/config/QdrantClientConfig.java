package io.kbrag.infrastructure.config;

import io.kbrag.domain.config.KbProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Builds the Qdrant REST client, only when a Qdrant URI is configured.
 *
 * <p>Lite mode leaves {@code QDRANT_URI} empty, so no client is created and the health endpoint does
 * not probe an engine that is not part of the deployment.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Configuration
@ConditionalOnExpression("!'${kb.qdrant.uri:}'.isBlank()")
public class QdrantClientConfig {

    /** Header Qdrant reads the access token from. */
    private static final String API_KEY_HEADER = "api-key";

    /**
     * Creates the Qdrant client from the configured URI.
     *
     * @param properties bound configuration
     * @return Qdrant REST client
     */
    @Bean
    public RestClient qdrantRestClient(KbProperties properties) {
        KbProperties.Qdrant config = properties.getQdrant();
        RestClient.Builder builder = RestClient.builder().baseUrl(config.getUri());
        if (!config.getApiKey().isBlank()) {
            builder.defaultHeader(API_KEY_HEADER, config.getApiKey());
        }
        log.info("qdrant client initialized, uri={}", config.getUri());
        return builder.build();
    }
}
