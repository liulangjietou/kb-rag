package io.kbrag.domain.port;

import io.kbrag.domain.model.ChatMessage;
import io.kbrag.domain.model.HealthStatus;

import java.util.List;

/**
 * Outbound port of the chat completion capability.
 *
 * <p>The system instruction is a separate argument rather than the first history entry so a caller
 * can never move an instruction into the conversation and change how the model is steered; every
 * implementation must place it in the system role and nowhere else.
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
     * Produces a completion for a single prompt.
     *
     * @param systemPrompt system instruction
     * @param userPrompt   user message
     * @return generated text
     */
    default String complete(String systemPrompt, String userPrompt) {
        return complete(systemPrompt, List.of(ChatMessage.user(userPrompt)));
    }

    /**
     * Produces a completion for a conversation.
     *
     * @param systemPrompt system instruction, placed in the system role
     * @param messages     conversation turns in chronological order, must not be empty
     * @return generated text
     */
    String complete(String systemPrompt, List<ChatMessage> messages);

    /**
     * Probes provider connectivity.
     *
     * @return probe outcome
     */
    HealthStatus healthCheck();
}
