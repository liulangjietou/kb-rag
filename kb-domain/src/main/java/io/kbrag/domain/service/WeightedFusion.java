package io.kbrag.domain.service;

import io.kbrag.domain.enums.FusionMode;
import io.kbrag.domain.enums.RetrievalSource;
import io.kbrag.domain.model.FusedChunk;
import io.kbrag.domain.model.FusionParams;
import io.kbrag.domain.model.ScoredChunk;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Weighted fusion of independent recall routes.
 *
 * <p>Design note. The raw route scores live on incompatible scales, so they are first mapped onto
 * {@code [0,1]} inside their own candidate list with a min-max transform:
 *
 * <pre>norm(d, route) = (score(d) - min(route)) / (max(route) - min(route))</pre>
 *
 * <p>The normalisation is deliberately per query and per route. A global normalisation would need a
 * corpus wide maximum that BM25 does not have, and normalising across routes would reintroduce the
 * scale problem this step exists to remove.
 *
 * <p>Two consequences are worth stating because they are properties of the method, not defects.
 * A route whose candidates all share one score collapses to a constant, which this implementation
 * maps to 1.0 so the route keeps its full weight instead of silently disappearing. And the top
 * candidate of every route always normalises to 1.0, so the absolute value of a fused score is only
 * meaningful inside one query, which is why a threshold never acts on it.
 *
 * <pre>fused(d) = w_vec * norm(d, vector) + (1 - w_vec) * norm(d, bm25)</pre>
 *
 * <p>A candidate missing from a route contributes zero for that route, which is what makes a
 * candidate found by both routes outrank one found by a single route at equal normalised score.
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class WeightedFusion implements FusionStrategy {

    /** Below this spread a route is treated as constant scored. */
    private static final double SCORE_SPREAD_EPSILON = 1e-9d;

    /** Normalised value assigned when a route produced a constant score. */
    private static final double CONSTANT_ROUTE_NORMALIZED = 1.0d;

    @Override
    public FusionMode mode() {
        return FusionMode.WEIGHTED;
    }

    @Override
    public List<FusedChunk> fuse(Map<RetrievalSource, List<ScoredChunk>> routeResults, FusionParams params) {
        if (MapUtils.isEmpty(routeResults)) {
            return List.of();
        }

        Map<String, Double> fusedByChunk = new LinkedHashMap<>();
        Map<String, Map<RetrievalSource, Integer>> ranksByChunk = new LinkedHashMap<>();
        Map<String, Map<RetrievalSource, Double>> rawByChunk = new LinkedHashMap<>();
        Map<String, Map<RetrievalSource, Double>> normalizedByChunk = new LinkedHashMap<>();

        for (Map.Entry<RetrievalSource, List<ScoredChunk>> entry : routeResults.entrySet()) {
            List<ScoredChunk> candidates = entry.getValue();
            if (CollectionUtils.isEmpty(candidates)) {
                continue;
            }
            RetrievalSource source = entry.getKey();
            double weight = weightOf(source, params);
            Map<String, Double> normalized = normalize(candidates);

            for (int i = 0; i < candidates.size(); i++) {
                ScoredChunk candidate = candidates.get(i);
                String chunkId = candidate.getChunkId();
                double normalizedScore = normalized.get(chunkId);
                fusedByChunk.merge(chunkId, weight * normalizedScore, Double::sum);
                ranksByChunk.computeIfAbsent(chunkId, id -> new EnumMap<>(RetrievalSource.class))
                        .put(source, i + 1);
                rawByChunk.computeIfAbsent(chunkId, id -> new EnumMap<>(RetrievalSource.class))
                        .put(source, candidate.getScore());
                normalizedByChunk.computeIfAbsent(chunkId, id -> new EnumMap<>(RetrievalSource.class))
                        .put(source, normalizedScore);
            }
        }

        List<FusedChunk> fused = new ArrayList<>(fusedByChunk.size());
        for (Map.Entry<String, Double> entry : fusedByChunk.entrySet()) {
            String chunkId = entry.getKey();
            Map<RetrievalSource, Integer> ranks = ranksByChunk.get(chunkId);
            fused.add(FusedChunk.builder()
                    .chunkId(chunkId)
                    .fusedScore(entry.getValue())
                    .fusionMode(FusionMode.WEIGHTED)
                    .primarySource(FusionSupport.bestSource(ranks))
                    .routeRanks(ranks)
                    .routeScores(rawByChunk.get(chunkId))
                    .normalizedScores(normalizedByChunk.get(chunkId))
                    .build());
        }
        fused.sort(Comparator.comparingDouble(FusedChunk::getFusedScore).reversed()
                .thenComparing(FusedChunk::getChunkId));
        return fused;
    }

    /**
     * Maps the candidates of one route onto {@code [0,1]}.
     *
     * @param candidates candidate list of a single route
     * @return normalised score per chunk id
     */
    private Map<String, Double> normalize(List<ScoredChunk> candidates) {
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (ScoredChunk candidate : candidates) {
            min = Math.min(min, candidate.getScore());
            max = Math.max(max, candidate.getScore());
        }
        double spread = max - min;
        Map<String, Double> normalized = new HashMap<>(candidates.size());
        for (ScoredChunk candidate : candidates) {
            normalized.put(candidate.getChunkId(), spread <= SCORE_SPREAD_EPSILON
                    ? CONSTANT_ROUTE_NORMALIZED
                    : (candidate.getScore() - min) / spread);
        }
        return normalized;
    }

    /**
     * Weight of one route. Routes beyond the two contract routes carry no weight, so adding a route
     * to the recall stage cannot silently change the ranking before its weight is defined.
     *
     * @param source route
     * @param params validated fusion parameters
     * @return weight in {@code [0,1]}
     */
    private double weightOf(RetrievalSource source, FusionParams params) {
        if (source == RetrievalSource.VECTOR) {
            return params.getWVector();
        }
        if (source == RetrievalSource.BM25) {
            return params.wBm25();
        }
        return 0.0d;
    }
}
