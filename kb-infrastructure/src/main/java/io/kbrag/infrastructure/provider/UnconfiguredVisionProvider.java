package io.kbrag.infrastructure.provider;

import io.kbrag.common.exception.ProviderErrorType;
import io.kbrag.common.exception.ProviderException;
import io.kbrag.domain.model.HealthStatus;
import io.kbrag.domain.port.VisionProvider;

/**
 * Vision provider of a deployment without a vision credential.
 *
 * <p>Every call fails fast with a classified exception rather than returning an empty string, so the
 * image stage records the asset as {@code SKIPPED} on purpose instead of storing a blank proxy that
 * would later look like a successful but useless call.
 *
 * @author owlzhangfq@gmail.com
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
    public String describeImage(byte[] content, String mediaType) {
        throw new ProviderException(PROVIDER_NAME, ProviderErrorType.AUTH_FAILED,
                "no vision provider configured");
    }

    @Override
    public HealthStatus healthCheck() {
        return HealthStatus.down("vision provider not configured");
    }
}
