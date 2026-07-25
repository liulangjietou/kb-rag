package io.kbrag.domain.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

/**
 * One turn of a conversation handed to the chat provider.
 *
 * <p>Only the two roles the rewrite stage needs are modelled; the system instruction is passed
 * separately so no caller can inject one through the history.
 */
@Getter
@AllArgsConstructor
@ToString(exclude = "content")
public class ChatMessage {

    /** Role literal of the OpenAI compatible schema. */
    public static final String ROLE_USER = "user";

    /** Role literal of the OpenAI compatible schema. */
    public static final String ROLE_ASSISTANT = "assistant";

    /** Role literal of the OpenAI compatible schema. */
    public static final String ROLE_SYSTEM = "system";

    /** Turn role, {@code user} or {@code assistant}. */
    private final String role;

    /** Turn text. */
    private final String content;

    /**
     * Builds a user turn.
     *
     * @param content turn text
     * @return message
     */
    public static ChatMessage user(String content) {
        return new ChatMessage(ROLE_USER, content);
    }

    /**
     * Builds an assistant turn.
     *
     * @param content turn text
     * @return message
     */
    public static ChatMessage assistant(String content) {
        return new ChatMessage(ROLE_ASSISTANT, content);
    }
}
