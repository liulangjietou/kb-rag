package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.app.openapi.KnowledgeCallResult;
import io.kbrag.common.context.RequestIdHolder;

import java.util.List;

/**
 * Open chat response of a non streamed call, requirement section 4.8.
 *
 * <p>{@code references} is the same {@code RetrievalNode} schema the search endpoint returns as {@code nodes},
 * shared deliberately so a client can render a citation card identically no matter which endpoint produced it.
 *
 * @param answer      generated answer
 * @param references  material the answer was generated from
 * @param requestId   correlation id of this call
 * @param degraded    degradation markers of the retrieval stage
 * @param appVersion  version literal that served the call
 * @param targetStage {@code release} or {@code beta}
 *
 * @author owlzhangfq@gmail.com
 */
public record KnowledgeChatResponse(
        String answer,
        List<RetrievalNodeResponse> references,
        @JsonProperty("request_id") String requestId,
        List<String> degraded,
        @JsonProperty("app_version") String appVersion,
        @JsonProperty("target_stage") String targetStage) {

    /**
     * Maps an application result onto the transport shape.
     *
     * @param result application result
     * @return transport response
     */
    public static KnowledgeChatResponse from(KnowledgeCallResult result) {
        return new KnowledgeChatResponse(
                result.getAnswer(),
                result.getNodes().stream().map(RetrievalNodeResponse::from).toList(),
                RequestIdHolder.get(),
                result.getDegraded(),
                result.getAppVersion(),
                result.getTargetStage() == null ? null : result.getTargetStage().code());
    }
}
