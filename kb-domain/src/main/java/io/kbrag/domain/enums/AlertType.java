package io.kbrag.domain.enums;

import java.util.Locale;

/**
 * Alert categories of the operations webhook.
 *
 * <p>The silence window is applied per category rather than globally: a task failure storm must not
 * mute the unrelated notification that the double write backlog is growing.
 *
 * @author owlzhangfq@gmail.com
 */
public enum AlertType {

    /** Same task type failed consecutively at least as many times as the configured threshold. */
    TASK_FAILURE,

    /** Share of degraded retrieval calls over the observation window exceeded the threshold. */
    RETRIEVAL_DEGRADE,

    /** Number of chunks waiting to reach a search engine exceeded the threshold. */
    SYNC_BACKLOG,

    /** Manual probe issued from the console to verify the webhook wiring. */
    TEST;

    /**
     * Lower case literal used in payloads and logs.
     *
     * @return wire value
     */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }
}
