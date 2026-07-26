package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.app.openapi.KnowledgeCallCommand;
import io.kbrag.domain.model.ChatMessage;
import io.kbrag.domain.service.RequestOverridePolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.collections4.CollectionUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Shared payload of the two open API endpoints, requirement section 4.8.
 *
 * <p><b>Every unknown field is collected, not ignored.</b> The four white listed overrides have real fields;
 * anything else the caller sent - {@code recall_top_k}, {@code fusion}, {@code rerank_enabled} - lands in
 * {@link #presentedOverrideKeys} through the any setter and is rejected by the policy in the application layer.
 * Jackson's default would silently drop them, which would let an agent believe its tuning took effect and draw
 * conclusions from results that were produced with entirely different parameters.
 *
 * <p>A class rather than a record because the any setter needs a mutable collector.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
public class KnowledgeCallRequest {

    /** User query. */
    @NotBlank(message = "must not be blank")
    private String query;

    /**
     * Application to call.
     *
     * <p>Not bean validated here because the console preview reaches the same shape through a path variable and
     * carries no {@code app_id} in its body; the open API endpoints validate it explicitly instead.
     */
    @JsonProperty("app_id")
    private String appId;

    /** Version literal, absent routes to the released version; naming a test version marks the call as beta. */
    @JsonProperty("app_version")
    private String appVersion;

    /** Conversation history, carried by the caller since the API keeps no session state. */
    @Valid
    private List<SearchRequest.MessageRequest> messages;

    /** White listed override: number of returned nodes. */
    @JsonProperty("top_n")
    @Min(value = 1, message = "must be at least 1")
    private Integer topN;

    /** White listed override: absolute score threshold. */
    @JsonProperty("score_threshold")
    @DecimalMin(value = "0.01", message = "must be at least 0.01")
    @DecimalMax(value = "1.0", message = "must be at most 1.0")
    private Double scoreThreshold;

    /** White listed override: narrowing metadata predicate. */
    @JsonProperty("metadata_filter")
    private SearchRequest.MetadataFilterRequest metadataFilter;

    /** White listed override: character budget of the returned content. */
    @JsonProperty("max_content_length")
    @Min(value = 1, message = "must be at least 1")
    private Integer maxContentLength;

    /** {@code true} asks the chat endpoint for a server sent event stream; ignored by the search endpoint. */
    private boolean stream;

    /** Names of the tuning fields the payload carried, including the ones with no field here. */
    @JsonIgnore
    private final Set<String> presentedOverrideKeys = new LinkedHashSet<>();

    /**
     * Collects a field this shape does not declare.
     *
     * @param name  field name as the payload spelled it
     * @param value field value, deliberately unused - only the name matters for the white list decision
     */
    @JsonAnySetter
    public void collectUnknown(String name, Object value) {
        presentedOverrideKeys.add(name);
    }

    /**
     * Maps the transport shape onto the application command.
     *
     * @return application command
     */
    public KnowledgeCallCommand toCommand() {
        Set<String> presented = new LinkedHashSet<>(presentedOverrideKeys);
        if (topN != null) {
            presented.add(RequestOverridePolicy.TOP_N);
        }
        if (scoreThreshold != null) {
            presented.add(RequestOverridePolicy.SCORE_THRESHOLD);
        }
        if (metadataFilter != null) {
            presented.add(RequestOverridePolicy.METADATA_FILTER);
        }
        if (maxContentLength != null) {
            presented.add(RequestOverridePolicy.MAX_CONTENT_LENGTH);
        }
        return KnowledgeCallCommand.builder()
                .appId(appId)
                .appVersion(appVersion)
                .query(query)
                .messages(toMessages())
                .topN(topN)
                .scoreThreshold(scoreThreshold)
                .metadataFilter(metadataFilter == null ? null : metadataFilter.toFilter())
                .maxContentLength(maxContentLength)
                .stream(stream)
                .presentedOverrideKeys(presented)
                .build();
    }

    /**
     * Maps the conversation history, dropping empty turns and normalising the role.
     *
     * <p>A caller supplied {@code system} role is turned into a user turn, exactly as the console's search path
     * does: accepting it would be a way to inject an instruction next to the one the application version froze.
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
