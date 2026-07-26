package io.kbrag.domain.model;

import lombok.Getter;
import lombok.ToString;

/**
 * One turn of a conversation handed to the chat provider.
 *
 * <p>Only the two roles the rewrite stage needs are modelled; the system instruction is passed
 * separately so no caller can inject one through the history.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
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
     * Jackson creator. The class only carried a lombok all-args constructor, which serializes fine
     * but cannot be deserialized - the first reader was the evaluation runner loading multi turn
     * cases back from the database, and every such run failed on parse.
     *
     * @param role    turn role
     * @param content turn text
     */
    @com.fasterxml.jackson.annotation.JsonCreator
    public ChatMessage(@com.fasterxml.jackson.annotation.JsonProperty("role") String role,
                       @com.fasterxml.jackson.annotation.JsonProperty("content") String content) {
        this.role = role;
        this.content = content;
    }

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
