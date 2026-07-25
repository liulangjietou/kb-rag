package io.kbrag.infrastructure.provider.embedding;

import io.kbrag.common.exception.ProviderErrorType;
import io.kbrag.common.exception.ProviderException;
import io.kbrag.domain.model.HealthStatus;
import io.kbrag.domain.port.EmbeddingProvider;

import java.util.List;

/**
 * Embedding provider used when no credential is configured, the zero key deployment.
 *
 * <p>Reporting the missing configuration through the same port instead of leaving the bean absent
 * keeps every collaborator free of null checks: the pipeline asks {@link #isConfigured()} once and
 * skips the embedding stage, and retrieval falls back to the BM25 single route. Calling
 * {@link #embed(List)} is a programming error and fails fast.
 */
public class UnconfiguredEmbeddingProvider implements EmbeddingProvider {

    /** Provider name recorded in the index registry and in the physical index name. */
    public static final String PROVIDER_NAME = "none";

    /** Model placeholder reported by the model status endpoint. */
    public static final String MODEL_NAME = "none";

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public String model() {
        return MODEL_NAME;
    }

    @Override
    public int dimension() {
        return 0;
    }

    @Override
    public boolean isConfigured() {
        return false;
    }

    @Override
    public int maxBatchSize() {
        return 0;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        throw new ProviderException(PROVIDER_NAME, ProviderErrorType.AUTH_FAILED,
                "no embedding provider configured");
    }

    @Override
    public HealthStatus healthCheck() {
        return HealthStatus.down("embedding provider not configured");
    }
}
