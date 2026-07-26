package io.kbrag.domain.enums;

/**
 * Lifecycle state of an evaluation run.
 *
 * @author owlzhangfq@gmail.com
 */
public enum RunStatus {

    /** Row created, execution not started yet. */
    PENDING,

    /** Cases are being judged. */
    RUNNING,

    /**
     * Execution finished. A run can be {@code SUCCESS} while carrying degraded cases; degradation is
     * reported separately through {@code case_degraded} and never silently turns a run into a failure.
     */
    SUCCESS,

    /**
     * Execution could not produce trustworthy metrics at all, for example a vector dependent mode
     * with no embedding provider configured.
     */
    FAILED
}
