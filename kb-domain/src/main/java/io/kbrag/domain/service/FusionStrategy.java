package io.kbrag.domain.service;

import io.kbrag.domain.enums.FusionMode;
import io.kbrag.domain.enums.RetrievalSource;
import io.kbrag.domain.model.FusedChunk;
import io.kbrag.domain.model.FusionParams;
import io.kbrag.domain.model.ScoredChunk;

import java.util.List;
import java.util.Map;

/**
 * Merges the candidate lists of the independent recall routes into one ordered list.
 *
 * <p>Modelled as a strategy rather than as a branch inside the retrieval service because the two
 * modes have nothing in common beyond their signature: one works on ranks, the other on normalised
 * magnitudes. A third mode would be a new implementation, not a third branch.
 */
public interface FusionStrategy {

    /**
     * Mode this implementation serves.
     *
     * @return fusion mode
     */
    FusionMode mode();

    /**
     * Fuses the candidate lists of several routes.
     *
     * @param routeResults candidates per route, each list ordered by descending route score
     * @param params       validated fusion parameters
     * @return fused candidates ordered by descending fusion score, chunk id as tie breaker
     */
    List<FusedChunk> fuse(Map<RetrievalSource, List<ScoredChunk>> routeResults, FusionParams params);
}
