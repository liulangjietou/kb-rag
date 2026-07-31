package io.kbrag.app.memory;

import io.kbrag.domain.entity.MemoryNode;
import io.kbrag.domain.enums.MemoryEventType;

import java.util.List;

/**
 * What one AddMemory call produced, the M19 contract.
 *
 * @param nodes   nodes written or revised by this call, in production order
 * @param profile profile view after this call's extraction, {@code null} when none was requested
 * @author owlzhangfq@gmail.com
 */
public record MemoryAddOutcome(List<AddedNode> nodes, MemoryProfileService.ProfileView profile) {

    /**
     * One node this call touched.
     *
     * @param node  stored row after the write
     * @param event whether the call created or revised it
     */
    public record AddedNode(MemoryNode node, MemoryEventType event) {
    }
}
