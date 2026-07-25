package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.app.retrieval.SearchOutcome;
import io.kbrag.common.context.RequestIdHolder;

import java.util.List;

/**
 * Retrieval result.
 *
 * <p>The request id is repeated inside the payload as well as in the envelope because agents keep the
 * data object rather than the envelope when they log a call.
 *
 * @param nodes     ordered result list
 * @param requestId correlation id of this call
 * @param degraded  degradation markers, empty when the full pipeline ran
 * @param applied   effective pipeline parameters
 */
public record SearchResponse(
        List<RetrievalNodeResponse> nodes,
        @JsonProperty("request_id") String requestId,
        List<String> degraded,
        AppliedResponse applied) {

    /**
     * Maps an application outcome onto the transport shape.
     *
     * @param outcome application outcome
     * @return transport response
     */
    public static SearchResponse from(SearchOutcome outcome) {
        return new SearchResponse(
                outcome.getNodes().stream().map(RetrievalNodeResponse::from).toList(),
                RequestIdHolder.get(),
                outcome.getDegraded(),
                AppliedResponse.from(outcome.getApplied()));
    }
}
