package io.kbrag.app.retrieval;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

/**
 * What the pipeline actually did, as opposed to what the request asked for.
 *
 * <p>Every field here answers a question the response would otherwise leave open when a stage was
 * switched off, degraded or overridden by a knowledge base default. Without it the debug console
 * cannot tell a query that was not rewritten from one whose rewrite happened to be identical, nor a
 * threshold that filtered nothing from one that was never applied.
 */
@Getter
@Builder
@ToString
public class AppliedInfo {

    /** Query the recall stage actually ran with. */
    private final String rewriteUsedQuery;

    /** Fusion strategy literal that produced the ordering. */
    private final String fusionMode;

    /** Score the threshold acted on, {@code none} when nothing was filtered. */
    private final String thresholdAppliedOn;
}
