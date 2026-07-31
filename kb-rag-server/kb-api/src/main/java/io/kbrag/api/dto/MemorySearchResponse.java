package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.app.memory.MemorySearchOutcome;

import java.util.List;

/**
 * SearchMemory response body, the M19 contract.
 *
 * @author owlzhangfq@gmail.com
 */
public record MemorySearchResponse(

        @JsonProperty("memory_nodes")
        List<MemoryNodeResponse> memoryNodes,

        List<MemoryProfileResponse> profiles,

        @JsonProperty("rewritten_query")
        String rewrittenQuery,

        @JsonProperty("intent_recalled")
        boolean intentRecalled) {

    /**
     * Maps the application outcome onto the transport shape.
     *
     * @param outcome search outcome
     * @return response body
     */
    public static MemorySearchResponse from(MemorySearchOutcome outcome) {
        return new MemorySearchResponse(
                outcome.nodes().stream()
                        .map(scored -> MemoryNodeResponse.from(scored.node(), scored.score()))
                        .toList(),
                outcome.profiles().stream().map(MemoryProfileResponse::from).toList(),
                outcome.rewrittenQuery(), outcome.intentRecalled());
    }
}
