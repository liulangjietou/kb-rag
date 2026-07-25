package io.kbrag.domain.enums;

/**
 * Lifecycle of an asynchronous task.
 */
public enum TaskStatus {

    /** Queued, not picked up yet. */
    PENDING,

    /** Currently executing. */
    RUNNING,

    /** Completed successfully. */
    SUCCESS,

    /** Failed, {@code fail_reason} carries the classified cause. */
    FAILED
}
