package io.kbrag.domain.service;

import io.kbrag.domain.enums.FusionMode;
import io.kbrag.domain.enums.RetrievalSource;
import io.kbrag.domain.model.FusedChunk;
import io.kbrag.domain.model.FusionParams;
import io.kbrag.domain.model.ScoredChunk;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Dispatches a fusion call to the strategy the request asked for.
 *
 * <p>Strategies are collected from the container instead of being listed here, so a new mode only
 * has to declare itself; the retrieval service keeps depending on this single collaborator and never
 * on a concrete strategy.
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class FusionRouter {

    private final Map<FusionMode, FusionStrategy> strategies = new EnumMap<>(FusionMode.class);

    public FusionRouter(List<FusionStrategy> availableStrategies) {
        for (FusionStrategy strategy : availableStrategies) {
            strategies.put(strategy.mode(), strategy);
        }
    }

    /**
     * Fuses the candidate lists with the requested strategy.
     *
     * @param routeResults candidates per route
     * @param params       validated fusion parameters
     * @return fused candidates ordered by descending fusion score
     */
    public List<FusedChunk> fuse(Map<RetrievalSource, List<ScoredChunk>> routeResults, FusionParams params) {
        FusionStrategy strategy = strategies.get(params.getMode());
        if (strategy == null) {
            throw new IllegalStateException("no fusion strategy registered for mode " + params.getMode());
        }
        return strategy.fuse(routeResults, params);
    }
}
