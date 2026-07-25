package io.kbrag.infrastructure.provider;

import io.kbrag.common.exception.ProviderErrorType;
import io.kbrag.common.exception.ProviderException;
import io.kbrag.domain.model.HealthStatus;
import io.kbrag.domain.port.ChatProvider;

/**
 * Placeholder chat provider. M1 exposes no question answering endpoint, the port exists so the chat
 * pipeline of M2 can be wired without reshaping the abstraction.
 */
public class UnconfiguredChatProvider implements ChatProvider {

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
    public String complete(String systemPrompt, String userPrompt) {
        throw new ProviderException(PROVIDER_NAME, ProviderErrorType.AUTH_FAILED,
                "no chat provider configured");
    }

    @Override
    public HealthStatus healthCheck() {
        return HealthStatus.down("chat provider not configured");
    }
}
