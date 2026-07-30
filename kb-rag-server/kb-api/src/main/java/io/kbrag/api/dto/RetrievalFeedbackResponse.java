package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.domain.entity.RetrievalFeedback;

/**
 * One feedback row of the console's feedback management view.
 *
 * <p>The raw query is returned to the console on purpose: the operator deciding whether to convert a
 * feedback has to read the question that was actually asked, and this view sits behind the console's
 * authentication like every other management screen.
 *
 * @param feedbackId      business identifier
 * @param kbId            knowledge base the query ran against
 * @param query           query the debug page ran
 * @param chunkId         chunk the verdict concerns
 * @param docId           owning document, {@code null} when the chunk was already deleted
 * @param verdict         {@code GOOD} or {@code BAD}
 * @param status          {@code NEW}, {@code CONVERTED} or {@code DISMISSED}
 * @param channel         {@code CONSOLE} or {@code OPEN_API}, the boundary the verdict arrived through
 * @param endUserId       caller asserted end user id of an open API row, {@code null} on console rows
 * @param convertedCaseId evaluation case created from this row, {@code null} until converted
 * @param note            free form operator note
 * @param createdAt       ISO submission timestamp
 *
 * @author owlzhangfq@gmail.com
 */
public record RetrievalFeedbackResponse(
        @JsonProperty("feedback_id") String feedbackId,
        @JsonProperty("kb_id") String kbId,
        String query,
        @JsonProperty("chunk_id") String chunkId,
        @JsonProperty("doc_id") String docId,
        String verdict,
        String status,
        String channel,
        @JsonProperty("end_user_id") String endUserId,
        @JsonProperty("converted_case_id") String convertedCaseId,
        String note,
        @JsonProperty("created_at") String createdAt) {

    /**
     * Maps a stored feedback row onto its response.
     *
     * @param row stored row
     * @return response
     */
    public static RetrievalFeedbackResponse from(RetrievalFeedback row) {
        return new RetrievalFeedbackResponse(
                row.getFeedbackId(),
                row.getKbId(),
                row.getQuery(),
                row.getChunkId(),
                row.getDocId(),
                row.getVerdict() == null ? null : row.getVerdict().name(),
                row.getStatus() == null ? null : row.getStatus().name(),
                row.getChannel() == null ? null : row.getChannel().name(),
                row.getEndUserId(),
                row.getConvertedCaseId(),
                row.getNote(),
                row.getCreatedAt() == null ? null : row.getCreatedAt().toString());
    }
}
