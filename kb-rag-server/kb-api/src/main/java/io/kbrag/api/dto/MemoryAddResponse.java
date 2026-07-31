package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.app.memory.MemoryAddOutcome;

import java.util.List;

/**
 * AddMemory response body, the M19 contract.
 *
 * @author owlzhangfq@gmail.com
 */
public record MemoryAddResponse(

        @JsonProperty("memory_nodes")
        List<AddedNodeResponse> memoryNodes,

        MemoryProfileResponse profile) {

    /**
     * Maps the application outcome onto the transport shape.
     *
     * @param outcome add outcome
     * @return response body
     */
    public static MemoryAddResponse from(MemoryAddOutcome outcome) {
        return new MemoryAddResponse(
                outcome.nodes().stream()
                        .map(added -> new AddedNodeResponse(added.node().getNodeId(),
                                added.node().getContent(), added.event().name()))
                        .toList(),
                outcome.profile() == null ? null : MemoryProfileResponse.from(outcome.profile()));
    }

    /**
     * One node the call touched.
     *
     * @param memoryNodeId node business id
     * @param content      remembered content after the write
     * @param event        whether the call created or revised the node
     */
    public record AddedNodeResponse(

            @JsonProperty("memory_node_id")
            String memoryNodeId,

            String content,

            String event) {
    }
}
