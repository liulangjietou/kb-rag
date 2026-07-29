package io.kbrag.app.retrieval;

import io.kbrag.domain.model.ScoredChunk;
import lombok.Getter;
import lombok.ToString;

import java.util.List;

/**
 * What the multimodal route contributed to one knowledge base of one call, the M14 contract section 6.3.
 *
 * <p>Modelled on {@code GraphRouteOutcome}: the candidates join the in base fusion exactly like the other
 * routes and are deduplicated on chunk id, so nothing downstream knows a route embedded a query into the
 * multimodal space rather than the text one. Unlike the graph route it carries no evidence - a multimodal
 * hit is the same chunk the text index holds, so the debug page has nothing extra to show for it.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@ToString
public final class MultimodalRouteOutcome {

    private static final MultimodalRouteOutcome SKIPPED = new MultimodalRouteOutcome(List.of(), null);

    /** Multimodal route candidates, ordered by descending relevance. */
    private final List<ScoredChunk> candidates;

    /** Degradation marker, {@code null} when the route ran or was never asked to. */
    private final String degradedReason;

    private MultimodalRouteOutcome(List<ScoredChunk> candidates, String degradedReason) {
        this.candidates = candidates;
        this.degradedReason = degradedReason;
    }

    /**
     * Outcome of a route that was not asked to run, or ran and found nothing.
     *
     * @return empty outcome carrying no marker
     */
    public static MultimodalRouteOutcome skipped() {
        return SKIPPED;
    }

    /**
     * Outcome of a route that ran.
     *
     * @param candidates candidates ordered by descending relevance
     * @return populated outcome
     */
    public static MultimodalRouteOutcome of(List<ScoredChunk> candidates) {
        return new MultimodalRouteOutcome(candidates, null);
    }

    /**
     * Outcome of a route the knowledge base asked for but the call could not run or refused to run.
     *
     * @param degradedReason marker reported to the caller
     * @return empty outcome carrying the marker
     */
    public static MultimodalRouteOutcome degraded(String degradedReason) {
        return new MultimodalRouteOutcome(List.of(), degradedReason);
    }
}
