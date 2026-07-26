package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.app.retrieval.RetrievalCommand;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.model.ChatMessage;
import io.kbrag.domain.model.MetadataFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.apache.commons.collections4.CollectionUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Retrieval payload.
 *
 * <p>Every tuning parameter is optional and every one of them takes effect immediately without being
 * persisted, which is what makes the debug console able to compare two configurations against the same
 * corpus. A {@code null} falls through to the knowledge base defaults and then to the deployment
 * defaults; it never means "off".
 *
 * @param query          user query
 * @param recallTopK     candidates recalled per route
 * @param topN           number of returned nodes
 * @param scoreThreshold absolute score threshold, only honoured when a comparable score exists
 * @param fusion         fusion strategy and its parameters
 * @param rerankEnabled  rerank switch, defaults to on when a rerank model is configured
 * @param rewriteEnabled query rewrite switch, defaults to off
 * @param messages       conversation used to resolve references during the rewrite
 * @param metadataFilter narrowing predicate pushed down to the engines
 *
 * @author owlzhangfq@gmail.com
 */
public record SearchRequest(
        @NotBlank(message = "must not be blank") String query,
        @JsonProperty("recall_top_k") Integer recallTopK,
        @JsonProperty("top_n") Integer topN,
        @JsonProperty("score_threshold")
        @DecimalMin(value = "0.01", message = "must be at least 0.01")
        @DecimalMax(value = "1.0", message = "must be at most 1.0") Double scoreThreshold,
        @Valid FusionRequest fusion,
        @JsonProperty("rerank_enabled") Boolean rerankEnabled,
        @JsonProperty("rewrite_enabled") Boolean rewriteEnabled,
        List<MessageRequest> messages,
        @JsonProperty("metadata_filter") MetadataFilterRequest metadataFilter) {

    /**
     * Fusion parameters.
     *
     * @param mode strategy literal, {@code rrf} or {@code weighted}
     * @param wVec vector weight of the weighted strategy, the BM25 weight is its complement
     * @param rrfK damping constant of the reciprocal rank strategy
     */
    public record FusionRequest(
            String mode,
            @JsonProperty("w_vec")
            @DecimalMin(value = "0.0", message = "must be at least 0")
            @DecimalMax(value = "1.0", message = "must be at most 1") Double wVec,
            @JsonProperty("rrf_k") @Min(value = 1, message = "must be at least 1") Integer rrfK) {
    }

    /**
     * One conversation turn.
     *
     * @param role    {@code user} or {@code assistant}
     * @param content turn text
     */
    public record MessageRequest(String role, String content) {
    }

    /**
     * Narrowing predicate.
     *
     * @param tagIds      document tag ids, matched as "any of"
     * @param sessionId   chat session id
     * @param sender      chat message sender
     * @param msgTimeFrom inclusive lower bound of the message timestamp in epoch milliseconds
     * @param msgTimeTo   inclusive upper bound of the message timestamp in epoch milliseconds
     */
    public record MetadataFilterRequest(
            @JsonProperty("tag_ids") List<String> tagIds,
            @JsonProperty("session_id") String sessionId,
            String sender,
            @JsonProperty("msg_time_from") Long msgTimeFrom,
            @JsonProperty("msg_time_to") Long msgTimeTo) {

        /**
         * Maps the transport shape onto the domain predicate.
         *
         * <p>Lives on the nested record rather than on its enclosing request so the open API can reuse the same
         * mapping: two definitions of the same filter would eventually disagree on the time range validation and
         * on what an empty filter means.
         *
         * @return domain filter, {@code null} when no condition was supplied
         */
        public MetadataFilter toFilter() {
            if (msgTimeFrom() != null && msgTimeTo() != null && msgTimeFrom() > msgTimeTo()) {
                throw BizException.invalidParam("msg_time_from must not be after msg_time_to");
            }
            MetadataFilter filter = MetadataFilter.builder()
                    .tagIds(tagIds())
                    .sessionId(sessionId())
                    .sender(sender())
                    .msgTimeFrom(msgTimeFrom())
                    .msgTimeTo(msgTimeTo())
                    .build();
            return filter.isEmpty() ? null : filter;
        }
    }

    /**
     * Maps the transport shape onto the application command.
     *
     * <p>This is the single fast-fail gate of the retrieval path: anything the bean validation
     * annotations cannot express is rejected here, and no stage downstream re-validates.
     *
     * @return application command
     */
    public RetrievalCommand toCommand() {
        return RetrievalCommand.builder()
                .query(query)
                .recallTopK(recallTopK)
                .topN(topN)
                .scoreThreshold(scoreThreshold)
                .fusionMode(fusion == null ? null : fusion.mode())
                .wVec(fusion == null ? null : fusion.wVec())
                .rrfK(fusion == null ? null : fusion.rrfK())
                .rerankEnabled(rerankEnabled)
                .rewriteEnabled(rewriteEnabled)
                .messages(toMessages())
                .metadataFilter(toMetadataFilter())
                .build();
    }

    private List<ChatMessage> toMessages() {
        if (CollectionUtils.isEmpty(messages)) {
            return List.of();
        }
        List<ChatMessage> turns = new ArrayList<>(messages.size());
        for (MessageRequest message : messages) {
            if (message.content() == null || message.content().isBlank()) {
                continue;
            }
            // Only the two conversation roles are accepted; a caller supplied system role would be a
            // way to replace the rewrite instruction, so anything unrecognised becomes a user turn.
            String role = ChatMessage.ROLE_ASSISTANT.equalsIgnoreCase(message.role())
                    ? ChatMessage.ROLE_ASSISTANT : ChatMessage.ROLE_USER;
            turns.add(new ChatMessage(role, message.content()));
        }
        return turns;
    }

    private MetadataFilter toMetadataFilter() {
        return metadataFilter == null ? null : metadataFilter.toFilter();
    }
}
