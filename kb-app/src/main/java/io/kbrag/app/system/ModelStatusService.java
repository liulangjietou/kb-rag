package io.kbrag.app.system;

import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.model.ModelStatus;
import io.kbrag.domain.port.EmbeddingProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Exposes the model configuration the console needs to grey out model backed controls before a user
 * triggers an operation that cannot succeed.
 */
@Service
@RequiredArgsConstructor
public class ModelStatusService {

    private final EmbeddingProvider embeddingProvider;
    private final KbProperties properties;

    /**
     * Builds the current model status.
     *
     * @return model status snapshot
     */
    public ModelStatus current() {
        return ModelStatus.builder()
                .embeddingConfigured(embeddingProvider.isConfigured())
                .vectorEngine(properties.getVector().resolved().code())
                .provider(embeddingProvider.providerName())
                .model(embeddingProvider.model())
                .dimension(embeddingProvider.dimension())
                .build();
    }
}
