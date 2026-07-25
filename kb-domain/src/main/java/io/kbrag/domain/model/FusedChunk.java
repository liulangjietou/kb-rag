package io.kbrag.domain.model;

import io.kbrag.domain.enums.RetrievalSource;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.util.Map;

/**
 * One candidate after reciprocal rank fusion.
 *
 * <p>The fusion score only orders the list; the per route ranks and scores are kept so the API can
 * report the score of the route that actually ranked the candidate best, and so the debug page can
 * show every route contribution.
 */
@Getter
@Builder
@ToString
public class FusedChunk {

    /** Chunk business id. */
    private final String chunkId;

    /** Reciprocal rank fusion score, ordering only. */
    private final double rrfScore;

    /** Route that ranked this candidate best, ties resolved towards the vector route. */
    private final RetrievalSource primarySource;

    /** One based rank per contributing route. */
    private final Map<RetrievalSource, Integer> routeRanks;

    /** Raw route score per contributing route. */
    private final Map<RetrievalSource, Double> routeScores;
}
