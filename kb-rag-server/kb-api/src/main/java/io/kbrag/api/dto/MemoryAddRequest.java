package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.app.memory.MemoryAddCommand;
import io.kbrag.domain.model.ChatMessage;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.collections4.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AddMemory payload of the memory open API, the M19 contract.
 *
 * <p>No library field, on purpose: the library comes from the authenticated key. The cross field
 * rule - at least one of {@code messages} and {@code custom_content} - is the application layer's
 * single gate, since bean validation cannot express it without duplicating the decision.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
public class MemoryAddRequest {

    /** Memory entity id; entities never see each other's memories. */
    @JsonProperty("user_id")
    @NotBlank(message = "user_id must not be blank")
    @Size(max = 64, message = "user_id must be at most 64 characters")
    private String userId;

    /** Conversation to extract from. */
    @Size(max = 50, message = "messages must carry at most 50 turns")
    private List<SearchRequest.MessageRequest> messages;

    /** Verbatim content written without extraction. */
    @JsonProperty("custom_content")
    @Size(max = 4000, message = "custom_content must be at most 4000 characters")
    private String customContent;

    /** Fragment rule to apply, absent takes the library's builtin default. */
    @JsonProperty("fragment_rule_id")
    private String fragmentRuleId;

    /** Profile rule to extract under, absent skips profile extraction. */
    @JsonProperty("profile_rule_id")
    private String profileRuleId;

    /** Caller metadata attached to every node this call creates, stored and returned verbatim. */
    @JsonProperty("meta_data")
    private Map<String, Object> metaData;

    /**
     * Maps the transport shape onto the application command.
     *
     * @return application command
     */
    public MemoryAddCommand toCommand() {
        return new MemoryAddCommand(userId, toMessages(), customContent,
                fragmentRuleId, profileRuleId, metaData);
    }

    /**
     * Maps the conversation, dropping empty turns and normalising the role - a caller supplied
     * {@code system} role becomes a user turn, same as every other conversation intake.
     *
     * @return chronological turns
     */
    private List<ChatMessage> toMessages() {
        if (CollectionUtils.isEmpty(messages)) {
            return List.of();
        }
        List<ChatMessage> turns = new ArrayList<>(messages.size());
        for (SearchRequest.MessageRequest message : messages) {
            if (message.content() == null || message.content().isBlank()) {
                continue;
            }
            String role = ChatMessage.ROLE_ASSISTANT.equalsIgnoreCase(message.role())
                    ? ChatMessage.ROLE_ASSISTANT : ChatMessage.ROLE_USER;
            turns.add(new ChatMessage(role, message.content()));
        }
        return turns;
    }
}
