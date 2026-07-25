package io.kbrag.infrastructure.provider;

import io.kbrag.common.exception.ProviderErrorType;
import io.kbrag.common.exception.ProviderException;
import io.kbrag.domain.model.ChatMessage;
import io.kbrag.domain.model.HealthStatus;
import io.kbrag.domain.port.ChatProvider;

import java.util.List;

/**
 * Placeholder chat provider selected when no chat credential is configured.
 *
 * <p>Callers are expected to branch on {@link #isConfigured()} and skip the stage; the throwing
 * implementation exists so a caller that forgets to branch fails loudly instead of silently
 * degrading the answer quality.
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
    public String complete(String systemPrompt, List<ChatMessage> messages) {
        throw new ProviderException(PROVIDER_NAME, ProviderErrorType.AUTH_FAILED,
                "no chat provider configured");
    }

    @Override
    public HealthStatus healthCheck() {
        return HealthStatus.down("chat provider not configured");
    }
}
