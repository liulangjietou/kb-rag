package io.kbrag.app.retrieval;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.util.List;

/**
 * What the pipeline actually did, as opposed to what the request asked for.
 *
 * <p>Every field here answers a question the response would otherwise leave open when a stage was
 * switched off, degraded or overridden by a knowledge base default. Without it the debug console
 * cannot tell a query that was not rewritten from one whose rewrite happened to be identical, nor a
 * threshold that filtered nothing from one that was never applied.
 *
 * @author owlzhangfq@gmail.com
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

    /**
     * Knowledge bases this call actually searched, requirement section 4.9.
     *
     * <p>The single field that separates the three outcomes of the routing stage: a selection, a fallback
     * to every linked base, and routing being off. Without it a response listing results from one base
     * cannot be told from a routing decision that discarded the other, which is the first thing an
     * operator needs to know when an answer is missing material.
     */
    private final List<String> routedKbIds;
}
