package io.kbrag.api;

import io.kbrag.domain.config.KbProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Entry point of the knowledge base main service.
 *
 * <p>Component scanning starts at {@code io.kbrag} so the API module assembles the application and
 * infrastructure layers; the dependency direction stays api to app to domain, with infrastructure
 * implementing the domain ports.
 */
@SpringBootApplication(scanBasePackages = "io.kbrag")
@MapperScan("io.kbrag.domain.mapper")
@EnableConfigurationProperties(KbProperties.class)
public class KbRagServerApplication {

    /**
     * Boots the service.
     *
     * @param args command line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(KbRagServerApplication.class, args);
    }
}
