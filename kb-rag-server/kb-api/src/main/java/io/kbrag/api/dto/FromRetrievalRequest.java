package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.enums.AnchorType;
import io.kbrag.domain.model.ChatMessage;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.apache.commons.collections4.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Payload of {@code POST /api/v1/eval-datasets/{datasetId}/cases/from-retrieval}, requirement section
 * 4.5 "one click collect from the retrieval debug page".
 *
 * @param query       query the debug page ran
 * @param messages    conversation history, optional
 * @param chunkIds    recalled chunks the operator selected as evidence
 * @param anchorType  forces {@code DOCUMENT} anchoring; {@code null} lets an image chunk decide
 *
 * @author owlzhangfq@gmail.com
 */
public record FromRetrievalRequest(
        @NotBlank(message = "must not be blank") String query,
        List<EvalCaseRequest.MessageRequest> messages,
        @JsonProperty("chunk_ids") @NotEmpty(message = "must not be empty") List<String> chunkIds,
        @JsonProperty("anchor_type") String anchorType) {

    /**
     * Resolves the anchor type override, the single fast-fail gate of this field.
     *
     * @return parsed override, {@code null} when the caller did not force one
     */
    public AnchorType parsedAnchorType() {
        if (anchorType == null || anchorType.isBlank()) {
            return null;
        }
        try {
            return AnchorType.valueOf(anchorType.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw BizException.invalidParam("anchor_type must be SPAN or DOCUMENT");
        }
    }

    /**
     * Maps the conversation turns onto the domain model.
     *
     * @return domain messages, empty when none were submitted
     */
    public List<ChatMessage> toMessages() {
        if (CollectionUtils.isEmpty(messages)) {
            return List.of();
        }
        List<ChatMessage> converted = new ArrayList<>(messages.size());
        for (EvalCaseRequest.MessageRequest message : messages) {
            converted.add(new ChatMessage(message.role(), message.content()));
        }
        return converted;
    }
}
