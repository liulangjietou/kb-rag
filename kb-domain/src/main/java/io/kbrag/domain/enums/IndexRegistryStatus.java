package io.kbrag.domain.enums;

/**
 * Lifecycle of a physical index registered in {@code t_kb_index_registry}.
 */
public enum IndexRegistryStatus {

    /** Physical index created, backfill in progress. */
    BUILDING,

    /** Alias currently points at this physical index. */
    ACTIVE,

    /** Superseded by a newer physical index, waiting for the cleanup task. */
    PENDING_CLEANUP
}
