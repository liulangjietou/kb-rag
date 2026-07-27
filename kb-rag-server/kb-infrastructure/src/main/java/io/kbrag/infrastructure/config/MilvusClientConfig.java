package io.kbrag.infrastructure.config;

import io.kbrag.domain.config.KbProperties;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

import java.net.URI;

/**
 * Builds the Milvus client, only when a Milvus URI is configured.
 *
 * <p>Lite mode leaves {@code MILVUS_URI} empty, so no client is created and the health endpoint
 * does not probe an engine that is not part of the deployment.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Configuration
@ConditionalOnExpression("!'${kb.milvus.uri:}'.isBlank()")
public class MilvusClientConfig {

    private static final int DEFAULT_GRPC_PORT = 19530;

    /**
     * Creates the Milvus client from the configured URI.
     *
     * @param properties bound configuration
     * @return Milvus client
     */
    @Bean(destroyMethod = "close")
    public MilvusServiceClient milvusServiceClient(KbProperties properties) {
        KbProperties.Milvus config = properties.getMilvus();
        URI uri = URI.create(config.getUri());
        int port = uri.getPort() > 0 ? uri.getPort() : DEFAULT_GRPC_PORT;
        ConnectParam.Builder builder = ConnectParam.newBuilder()
                .withHost(uri.getHost())
                .withPort(port);
        if (!config.getToken().isBlank()) {
            builder.withToken(config.getToken());
        }
        log.info("milvus client initialized, uri={}", config.getUri());
        return new MilvusServiceClient(builder.build());
    }
}
