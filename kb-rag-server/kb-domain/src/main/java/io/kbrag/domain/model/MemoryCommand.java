package io.kbrag.domain.model;

import io.kbrag.domain.enums.MemoryEventType;

/**
 * One write the fragment extraction asked for, the M19 contract.
 *
 * @param event  ADD writes a new node, UPDATE revises an existing one in place
 * @param nodeId node to revise, only present on UPDATE
 * @param content memory text
 * @author owlzhangfq@gmail.com
 */
public record MemoryCommand(MemoryEventType event, String nodeId, String content) {
}
