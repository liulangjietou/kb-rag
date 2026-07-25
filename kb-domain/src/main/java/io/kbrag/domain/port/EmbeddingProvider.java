package io.kbrag.domain.port;

import io.kbrag.common.exception.ProviderException;
import io.kbrag.domain.model.HealthStatus;

import java.util.List;

/**
 * Outbound port of the embedding capability.
 *
 * <p>Implementations declare their own model metadata; the dimension is fixed at index creation
 * time, so a model whose dimension differs from the configured one forces a rebuild instead of
 * being adapted in place.
 */
public interface EmbeddingProvider {

    /**
     * Provider implementation name, for example {@code dashscope} or {@code none}.
     *
     * @return provider name
     */
    String providerName();

    /**
     * Model identifier this instance was configured with.
     *
     * @return model name
     */
    String model();

    /**
     * Vector dimension produced by {@link #model()}.
     *
     * @return dimension, 0 when the provider is not configured
     */
    int dimension();

    /**
     * Tells whether the provider holds a usable credential.
     *
     * <p>The zero key deployment relies on this flag to skip the embedding stage entirely.
     *
     * @return {@code true} when embedding calls can be issued
     */
    boolean isConfigured();

    /**
     * Maximum number of texts a single provider request accepts.
     *
     * @return batch size, callers must not exceed it
     */
    int maxBatchSize();

    /**
     * Embeds a batch of texts, preserving the input order.
     *
     * @param texts input texts, size must not exceed {@link #maxBatchSize()}
     * @return one vector per input text
     * @throws ProviderException classified provider failure
     */
    List<float[]> embed(List<String> texts);

    /**
     * Probes provider connectivity without consuming a meaningful quota.
     *
     * @return probe outcome
     */
    HealthStatus healthCheck();
}
