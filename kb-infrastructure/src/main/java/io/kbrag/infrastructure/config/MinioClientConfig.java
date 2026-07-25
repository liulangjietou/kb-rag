package io.kbrag.infrastructure.config;

import io.kbrag.domain.config.KbProperties;
import io.minio.MinioClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the MinIO client used for original files and parse artifacts.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Configuration
public class MinioClientConfig {

    /**
     * Creates the MinIO client from the configured endpoint and credentials.
     *
     * @param properties bound configuration
     * @return MinIO client
     */
    @Bean
    public MinioClient minioClient(KbProperties properties) {
        KbProperties.Minio config = properties.getMinio();
        if (config.getAccessKey().isBlank() || config.getSecretKey().isBlank()) {
            // Object storage credentials are a hard requirement of the deployment, unlike the model
            // key which has a documented zero key fallback. Failing here with an actionable message
            // beats letting the SDK raise an opaque argument error.
            throw new IllegalStateException(
                    "MINIO_ACCESS_KEY and MINIO_SECRET_KEY must be set, see the README configuration section");
        }
        log.info("minio client initialized, endpoint={}, bucket={}", config.getEndpoint(), config.getBucket());
        return MinioClient.builder()
                .endpoint(config.getEndpoint())
                .credentials(config.getAccessKey(), config.getSecretKey())
                .build();
    }
}
