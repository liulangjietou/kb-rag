package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.app.openapi.KnowledgeCallResult;
import io.kbrag.common.context.RequestIdHolder;

import java.util.List;

/**
 * Open search response, requirement section 4.8.
 *
 * <p>The resolved version travels with the result: an agent that omitted {@code app_version} still has to be
 * able to tell which configuration produced the answer it is about to act on, and after a release that is not
 * the version it saw last time.
 *
 * @param nodes       ordered result list, the shared {@code RetrievalNode} schema
 * @param requestId   correlation id of this call
 * @param degraded    degradation markers, empty when the full pipeline ran
 * @param applied     effective pipeline parameters
 * @param appVersion  version literal that served the call
 * @param targetStage {@code release} or {@code beta}
 *
 * @author owlzhangfq@gmail.com
 */
public record KnowledgeSearchResponse(
        List<RetrievalNodeResponse> nodes,
        @JsonProperty("request_id") String requestId,
        List<String> degraded,
        AppliedResponse applied,
        @JsonProperty("app_version") String appVersion,
        @JsonProperty("target_stage") String targetStage) {

    /**
     * Maps an application result onto the transport shape.
     *
     * @param result application result
     * @return transport response
     */
    public static KnowledgeSearchResponse from(KnowledgeCallResult result) {
        return new KnowledgeSearchResponse(
                result.getNodes().stream().map(RetrievalNodeResponse::from).toList(),
                RequestIdHolder.get(),
                result.getDegraded(),
                result.getApplied() == null ? null : AppliedResponse.from(result.getApplied()),
                result.getAppVersion(),
                result.getTargetStage() == null ? null : result.getTargetStage().code());
    }
}
