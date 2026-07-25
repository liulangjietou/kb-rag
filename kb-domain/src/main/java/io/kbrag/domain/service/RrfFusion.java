package io.kbrag.domain.service;

import io.kbrag.domain.enums.RetrievalSource;
import io.kbrag.domain.model.FusedChunk;
import io.kbrag.domain.model.ScoredChunk;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal rank fusion of independent recall routes.
 *
 * <p>Design note. Every route is scored on its own scale: cosine similarity is bounded, BM25 is
 * not, so the raw values cannot be summed. Reciprocal rank fusion discards the magnitudes and only
 * keeps the position, which makes the routes comparable without any normalisation step:
 *
 * <pre>score(d) = sum over routes of 1 / (k + rank(d, route))</pre>
 *
 * <p>The constant {@code k} damps the influence of the very top positions; the contract fixes it
 * at 60, the value the original publication reports as stable across corpora. A document missing
 * from a route simply contributes nothing for that route.
 *
 * <p>The result is ordered by descending fusion score with the chunk id as tie breaker, so the same
 * input always produces the same output and evaluation runs stay reproducible.
 */
@Component
public class RrfFusion {

    /** Contract default damping constant. */
    public static final int DEFAULT_K = 60;

    /**
     * Fuses the candidate lists of several routes.
     *
     * @param routeResults candidates per route, each list ordered by descending route score
     * @param k            damping constant, must be positive
     * @return fused candidates ordered by descending fusion score
     */
    public List<FusedChunk> fuse(Map<RetrievalSource, List<ScoredChunk>> routeResults, int k) {
        if (k <= 0) {
            throw new IllegalArgumentException("rrf k must be positive");
        }
        if (MapUtils.isEmpty(routeResults)) {
            return List.of();
        }

        Map<String, Double> scoreByChunk = new LinkedHashMap<>();
        Map<String, Map<RetrievalSource, Integer>> ranksByChunk = new LinkedHashMap<>();
        Map<String, Map<RetrievalSource, Double>> routeScoresByChunk = new LinkedHashMap<>();

        for (Map.Entry<RetrievalSource, List<ScoredChunk>> entry : routeResults.entrySet()) {
            List<ScoredChunk> candidates = entry.getValue();
            if (CollectionUtils.isEmpty(candidates)) {
                continue;
            }
            RetrievalSource source = entry.getKey();
            for (int i = 0; i < candidates.size(); i++) {
                ScoredChunk candidate = candidates.get(i);
                int rank = i + 1;
                String chunkId = candidate.getChunkId();
                scoreByChunk.merge(chunkId, 1.0d / (k + rank), Double::sum);
                ranksByChunk.computeIfAbsent(chunkId, id -> new EnumMap<>(RetrievalSource.class))
                        .put(source, rank);
                routeScoresByChunk.computeIfAbsent(chunkId, id -> new EnumMap<>(RetrievalSource.class))
                        .put(source, candidate.getScore());
            }
        }

        List<FusedChunk> fused = new ArrayList<>(scoreByChunk.size());
        for (Map.Entry<String, Double> entry : scoreByChunk.entrySet()) {
            String chunkId = entry.getKey();
            Map<RetrievalSource, Integer> ranks = ranksByChunk.get(chunkId);
            fused.add(FusedChunk.builder()
                    .chunkId(chunkId)
                    .rrfScore(entry.getValue())
                    .primarySource(bestSource(ranks))
                    .routeRanks(ranks)
                    .routeScores(routeScoresByChunk.get(chunkId))
                    .build());
        }
        fused.sort(Comparator.comparingDouble(FusedChunk::getRrfScore).reversed()
                .thenComparing(FusedChunk::getChunkId));
        return fused;
    }

    /**
     * Picks the route that ranked a candidate best; ties fall to the route declared first in
     * {@link RetrievalSource}, which is the vector route.
     *
     * @param ranks one based rank per contributing route
     * @return best route
     */
    private RetrievalSource bestSource(Map<RetrievalSource, Integer> ranks) {
        RetrievalSource best = null;
        int bestRank = Integer.MAX_VALUE;
        for (Map.Entry<RetrievalSource, Integer> entry : ranks.entrySet()) {
            if (entry.getValue() < bestRank) {
                bestRank = entry.getValue();
                best = entry.getKey();
            }
        }
        return best;
    }
}
