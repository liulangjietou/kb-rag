package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.entity.EvalCase;
import io.kbrag.domain.model.ChatMessage;
import io.kbrag.domain.model.EvalEvidence;

import java.util.List;

/**
 * Full field view of one evaluation case.
 *
 * @param caseId         business identifier
 * @param datasetId      owning data set
 * @param query          user query
 * @param messages       conversation history, {@code null} for a single turn case
 * @param expectedAnswer reference answer
 * @param expectedRefusal whether the correct final answer should refuse
 * @param anchorType     anchoring granularity
 * @param evidences      evidence anchors
 * @param status         lifecycle state
 * @param source         origin of the case
 * @param note           free text operator note
 * @param createdAt      ISO creation timestamp
 *
 * @author owlzhangfq@gmail.com
 */
public record EvalCaseResponse(
        @JsonProperty("case_id") String caseId,
        @JsonProperty("dataset_id") String datasetId,
        String query,
        List<MessageView> messages,
        @JsonProperty("expected_answer") String expectedAnswer,
        @JsonProperty("expected_refusal") boolean expectedRefusal,
        @JsonProperty("anchor_type") String anchorType,
        List<EvalEvidenceResponse> evidences,
        String status,
        String source,
        String note,
        @JsonProperty("created_at") String createdAt) {

    /**
     * Maps a stored case onto its response.
     *
     * @param evalCase stored case
     * @return response
     */
    public static EvalCaseResponse from(EvalCase evalCase) {
        List<ChatMessage> messages = evalCase.getMessages() == null ? null
                : JsonUtil.parse(evalCase.getMessages(), new TypeReference<List<ChatMessage>>() {
                });
        List<EvalEvidence> evidences = JsonUtil.parse(evalCase.getEvidences(),
                new TypeReference<List<EvalEvidence>>() {
                });
        return new EvalCaseResponse(
                evalCase.getCaseId(),
                evalCase.getDatasetId(),
                evalCase.getQuery(),
                messages == null ? null : messages.stream().map(MessageView::from).toList(),
                evalCase.getExpectedAnswer(),
                Boolean.TRUE.equals(evalCase.getExpectedRefusal()),
                evalCase.getAnchorType() == null ? null : evalCase.getAnchorType().name(),
                evidences == null ? List.of() : evidences.stream().map(EvalEvidenceResponse::from).toList(),
                evalCase.getStatus() == null ? null : evalCase.getStatus().name(),
                evalCase.getSource() == null ? null : evalCase.getSource().name(),
                evalCase.getNote(),
                evalCase.getCreatedAt() == null ? null : evalCase.getCreatedAt().toString());
    }

    /**
     * One conversation turn.
     *
     * @param role    {@code user} or {@code assistant}
     * @param content turn text
     */
    public record MessageView(String role, String content) {

        private static MessageView from(ChatMessage message) {
            return new MessageView(message.getRole(), message.getContent());
        }
    }
}
