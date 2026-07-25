package io.kbrag.infrastructure.provider;

import io.kbrag.common.exception.ProviderErrorType;
import io.kbrag.common.exception.ProviderException;
import io.kbrag.domain.model.HealthStatus;
import io.kbrag.domain.port.RerankProvider;

import java.util.List;

/**
 * Placeholder rerank provider. M1 has no rerank stage, the port exists so the retrieval pipeline of
 * M2 can be wired without reshaping the abstraction.
 */
public class UnconfiguredRerankProvider implements RerankProvider {

    /** Provider name reported while the capability is unconfigured. */
    public static final String PROVIDER_NAME = "none";

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public String model() {
        return PROVIDER_NAME;
    }

    @Override
    public boolean isConfigured() {
        return false;
    }

    @Override
    public List<Double> rerank(String query, List<String> documents) {
        throw new ProviderException(PROVIDER_NAME, ProviderErrorType.AUTH_FAILED,
                "no rerank provider configured");
    }

    @Override
    public HealthStatus healthCheck() {
        return HealthStatus.down("rerank provider not configured");
    }
}
