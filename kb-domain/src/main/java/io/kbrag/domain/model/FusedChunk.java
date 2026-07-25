package io.kbrag.domain.model;

import io.kbrag.domain.enums.FusionMode;
import io.kbrag.domain.enums.RetrievalSource;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.util.Map;

/**
 * One candidate after fusion of the recall routes.
 *
 * <p>The fusion score only orders the list; the per route ranks, raw scores and normalised scores
 * are kept so the API can report whichever score the threshold actually acts on and so the debug
 * page can show every route contribution side by side.
 */
@Getter
@Builder
@ToString
public class FusedChunk {

    /** Chunk business id. */
    private final String chunkId;

    /** Fusion score, ordering only. */
    private final double fusedScore;

    /** Strategy that produced {@link #fusedScore}. */
    private final FusionMode fusionMode;

    /** Route that ranked this candidate best, ties resolved towards the vector route. */
    private final RetrievalSource primarySource;

    /** One based rank per contributing route. */
    private final Map<RetrievalSource, Integer> routeRanks;

    /** Raw route score per contributing route. */
    private final Map<RetrievalSource, Double> routeScores;

    /**
     * Min-max normalised route score, only populated by the weighted strategy because reciprocal
     * rank fusion deliberately discards magnitudes.
     */
    private final Map<RetrievalSource, Double> normalizedScores;

    /**
     * Reads the raw score of one route.
     *
     * @param source route
     * @return raw score, {@code null} when the route did not recall this candidate
     */
    public Double routeScore(RetrievalSource source) {
        return routeScores == null ? null : routeScores.get(source);
    }

    /**
     * Reads the min-max normalised score of one route.
     *
     * @param source route
     * @return normalised score, {@code null} outside the weighted strategy
     */
    public Double normalizedScore(RetrievalSource source) {
        return normalizedScores == null ? null : normalizedScores.get(source);
    }
}
