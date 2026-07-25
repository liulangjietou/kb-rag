package io.kbrag.infrastructure.provider;

import io.kbrag.common.exception.ProviderErrorType;
import io.kbrag.common.exception.ProviderException;
import io.kbrag.domain.model.HealthStatus;
import io.kbrag.domain.port.VisionProvider;

/**
 * Placeholder vision provider. M1 does not generate textual proxies for images, the port exists so
 * the image pipeline of M2 can be wired without reshaping the abstraction.
 */
public class UnconfiguredVisionProvider implements VisionProvider {

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
    public String describeImage(String imageObjectKey) {
        throw new ProviderException(PROVIDER_NAME, ProviderErrorType.AUTH_FAILED,
                "no vision provider configured");
    }

    @Override
    public HealthStatus healthCheck() {
        return HealthStatus.down("vision provider not configured");
    }
}
