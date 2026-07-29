package io.kbrag.infrastructure.provider.embedding;

import io.kbrag.common.exception.ProviderErrorType;
import io.kbrag.common.exception.ProviderException;
import io.kbrag.domain.model.HealthStatus;
import io.kbrag.domain.model.ImageInput;
import io.kbrag.domain.port.MultimodalEmbeddingProvider;

import java.util.List;

/**
 * Multimodal embedding provider used when no credential is configured, the M14 contract section 6.1.
 *
 * <p>Reporting the missing configuration through the same port instead of leaving the bean absent
 * keeps every collaborator free of null checks: the index pipeline asks {@link #isConfigured()} once
 * and skips the multimodal vectors, and retrieval skips the third route. Calling {@link #embedTexts}
 * or {@link #embedImages} is a programming error and fails fast.
 *
 * @author owlzhangfq@gmail.com
 */
public class NoopMultimodalEmbeddingProvider implements MultimodalEmbeddingProvider {

    /** Provider name recorded in the multimodal index registry and physical index name. */
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
    public List<float[]> embedTexts(List<String> texts) {
        throw new ProviderException(PROVIDER_NAME, ProviderErrorType.AUTH_FAILED,
                "no multimodal embedding provider configured");
    }

    @Override
    public List<float[]> embedImages(List<ImageInput> images) {
        throw new ProviderException(PROVIDER_NAME, ProviderErrorType.AUTH_FAILED,
                "no multimodal embedding provider configured");
    }

    @Override
    public HealthStatus healthCheck() {
        return HealthStatus.down("multimodal embedding provider not configured");
    }
}
