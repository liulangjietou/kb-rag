package io.kbrag.domain.enums;

/**
 * Synchronization state of one chunk against one physical index.
 */
public enum IndexSyncStatus {

    /** Registered but not written yet. */
    PENDING,

    /** Successfully written to the physical index. */
    SYNCED,

    /** Write failed, picked up by the compensation scan. */
    FAILED
}
