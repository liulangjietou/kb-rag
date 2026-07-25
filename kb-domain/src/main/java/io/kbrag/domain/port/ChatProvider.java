package io.kbrag.domain.port;

import io.kbrag.domain.model.HealthStatus;

/**
 * Outbound port of the chat completion capability.
 *
 * <p>M1 only defines the contract and ships a disabled placeholder implementation; question
 * answering lands in M2.
 */
public interface ChatProvider {

    /**
     * Provider implementation name.
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
     * Tells whether the provider holds a usable credential.
     *
     * @return {@code true} when chat calls can be issued
     */
    boolean isConfigured();

    /**
     * Produces a completion for a prompt.
     *
     * @param systemPrompt system instruction
     * @param userPrompt   user message
     * @return generated text
     */
    String complete(String systemPrompt, String userPrompt);

    /**
     * Probes provider connectivity.
     *
     * @return probe outcome
     */
    HealthStatus healthCheck();
}
