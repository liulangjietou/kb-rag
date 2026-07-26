package io.kbrag.app.graph;

import io.kbrag.domain.model.GraphChunkRelevance;
import io.kbrag.domain.model.ScoredChunk;
import lombok.Getter;
import lombok.ToString;

import java.util.List;
import java.util.Map;

/**
 * What the graph route contributed to one knowledge base of one call, requirement section 4.9.
 *
 * <p>Two products, on purpose. The candidates join the in base fusion exactly like the other two routes,
 * so nothing downstream knows a third route exists; the evidence travels beside them because the hop
 * count and the matched entity names are what the debug page needs and they have no place in a score.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@ToString
public final class GraphRouteOutcome {

    private static final GraphRouteOutcome SKIPPED = new GraphRouteOutcome(List.of(), Map.of(), null);

    /** Graph route candidates, ordered by descending relevance. */
    private final List<ScoredChunk> candidates;

    /** Relevance detail per chunk id, for the node metadata. */
    private final Map<String, GraphChunkRelevance> evidenceByChunk;

    /** Degradation marker, {@code null} when the route ran or was never asked to. */
    private final String degradedReason;

    private GraphRouteOutcome(List<ScoredChunk> candidates,
                              Map<String, GraphChunkRelevance> evidenceByChunk,
                              String degradedReason) {
        this.candidates = candidates;
        this.evidenceByChunk = evidenceByChunk;
        this.degradedReason = degradedReason;
    }

    /**
     * Outcome of a route that was not asked to run, or ran and found nothing.
     *
     * @return empty outcome carrying no marker
     */
    public static GraphRouteOutcome skipped() {
        return SKIPPED;
    }

    /**
     * Outcome of a route that ran.
     *
     * @param candidates      candidates ordered by descending relevance
     * @param evidenceByChunk relevance detail per chunk id
     * @return populated outcome
     */
    public static GraphRouteOutcome of(List<ScoredChunk> candidates,
                                       Map<String, GraphChunkRelevance> evidenceByChunk) {
        return new GraphRouteOutcome(candidates, evidenceByChunk, null);
    }

    /**
     * Outcome of a route the knowledge base asked for but the deployment could not run.
     *
     * @param degradedReason marker reported to the caller
     * @return empty outcome carrying the marker
     */
    public static GraphRouteOutcome degraded(String degradedReason) {
        return new GraphRouteOutcome(List.of(), Map.of(), degradedReason);
    }
}
