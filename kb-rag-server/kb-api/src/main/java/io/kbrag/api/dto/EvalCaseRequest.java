package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.app.eval.EvalCaseCommand;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.enums.AnchorType;
import io.kbrag.domain.model.ChatMessage;
import io.kbrag.domain.model.EvalEvidence;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.apache.commons.collections4.CollectionUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Create or edit payload of one evaluation case, requirement section 4.5.
 *
 * @param query          user query
 * @param messages       optional conversation history, absent keeps the case single turn
 * @param expectedAnswer reference answer, optional
 * @param expectedRefusal whether the correct final answer should refuse
 * @param anchorType     {@code SPAN} or {@code DOCUMENT}, case insensitive
 * @param evidences      evidence anchors, at least one required
 * @param note           free text operator note
 *
 * @author owlzhangfq@gmail.com
 */
public record EvalCaseRequest(
        @NotBlank(message = "must not be blank") String query,
        List<MessageRequest> messages,
        @JsonProperty("expected_answer") String expectedAnswer,
        @JsonProperty("expected_refusal") boolean expectedRefusal,
        @JsonProperty("anchor_type") @NotBlank(message = "must not be blank") String anchorType,
        @NotEmpty(message = "at least one evidence is required") @Valid List<EvalEvidenceRequest> evidences,
        String note) {

    /**
     * Maps the transport shape onto the application command, the single fast-fail gate of this payload.
     *
     * @return application command
     */
    public EvalCaseCommand toCommand() {
        AnchorType parsedAnchorType = parseAnchorType();
        List<EvalEvidence> parsedEvidences = evidences.stream().map(EvalEvidenceRequest::toEvidence).toList();
        return EvalCaseCommand.builder()
                .query(query)
                .messages(toMessages())
                .expectedAnswer(expectedAnswer)
                .expectedRefusal(expectedRefusal)
                .anchorType(parsedAnchorType)
                .evidences(parsedEvidences)
                .note(note)
                .build();
    }

    private AnchorType parseAnchorType() {
        try {
            return AnchorType.valueOf(anchorType.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw BizException.invalidParam("anchor_type must be SPAN or DOCUMENT");
        }
    }

    private List<ChatMessage> toMessages() {
        if (CollectionUtils.isEmpty(messages)) {
            return null;
        }
        List<ChatMessage> converted = new ArrayList<>(messages.size());
        for (MessageRequest message : messages) {
            converted.add(new ChatMessage(message.role(), message.content()));
        }
        return converted;
    }

    /**
     * One conversation turn.
     *
     * @param role    {@code user} or {@code assistant}
     * @param content turn text
     */
    public record MessageRequest(@NotNull String role, @NotBlank(message = "must not be blank") String content) {
    }
}
