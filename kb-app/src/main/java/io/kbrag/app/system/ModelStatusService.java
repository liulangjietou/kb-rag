package io.kbrag.app.system;

import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.model.ModelStatus;
import io.kbrag.domain.port.ChatProvider;
import io.kbrag.domain.port.EmbeddingProvider;
import io.kbrag.domain.port.RerankProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Exposes the model configuration the console needs to grey out model backed controls before a user
 * triggers an operation that cannot succeed.
 *
 * <p>The three capabilities are read from their own providers rather than from one credential flag,
 * because they are configured independently: a deployment may hold a rerank key and no chat key, and
 * telling the console otherwise would disable a control that actually works.
 *
 * @author owlzhangfq@gmail.com
 */
@Service
@RequiredArgsConstructor
public class ModelStatusService {

    private final EmbeddingProvider embeddingProvider;
    private final RerankProvider rerankProvider;
    private final ChatProvider chatProvider;
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
                .rerankConfigured(rerankProvider.isConfigured())
                .rerankProvider(rerankProvider.providerName())
                .rerankModel(rerankProvider.model())
                .chatConfigured(chatProvider.isConfigured())
                .chatProvider(chatProvider.providerName())
                .chatModel(chatProvider.model())
                .build();
    }
}
