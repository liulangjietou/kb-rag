package io.kbrag.domain.service;

import io.kbrag.domain.model.GraphChunkRelevance;
import io.kbrag.domain.model.GraphTraceRow;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the paths the graph returned into the in base ranking of the graph route,
 * requirement section 4.9 "relevance = path hop reciprocal times entity match score".
 *
 * <pre>relevance(chunk, entity) = matchScore(entity) / (1 + hops)</pre>
 *
 * <p><b>Why the reciprocal and not a decay constant.</b> The formula has to be readable off a debug page:
 * an operator seeing {@code 0.4} next to "1 hop" must be able to derive that the entity matched at
 * {@code 0.8}, and a tunable decay would make every displayed number depend on a knob nobody remembers
 * setting. A direct hit keeps the full match score, one hop halves it, two hops keep a third.
 *
 * <p><b>Several entities reaching the same chunk take the maximum, not the sum.</b> A chunk mentioning
 * two entities of the query is not twice as relevant as one mentioning the best of them - summing would
 * make long chunks win purely by naming more things, which is the failure mode the requirement's "take
 * the max" clause exists to avoid.
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class GraphRelevanceScorer {

    /** Matched entity names reported per chunk, requirement section 4.9 {@code graph_entities}. */
    public static final int MAX_REPORTED_ENTITIES = 5;

    /**
     * Ranks the chunks the traversal reached.
     *
     * @param rows  one row per (chunk, matched entity) pair, in any order
     * @param limit chunks the graph route contributes at most
     * @return ranking by descending relevance, chunk id breaking ties so a run is reproducible
     */
    public List<GraphChunkRelevance> rank(List<GraphTraceRow> rows, int limit) {
        if (CollectionUtils.isEmpty(rows) || limit < 1) {
            return List.of();
        }
        Map<String, ChunkAccumulator> byChunk = new LinkedHashMap<>();
        for (GraphTraceRow row : rows) {
            byChunk.computeIfAbsent(row.chunkId(), ChunkAccumulator::new).accept(row);
        }
        List<GraphChunkRelevance> ranked = new ArrayList<>(byChunk.size());
        for (ChunkAccumulator accumulator : byChunk.values()) {
            ranked.add(accumulator.toRelevance());
        }
        ranked.sort(Comparator.comparingDouble(GraphChunkRelevance::score).reversed()
                .thenComparing(GraphChunkRelevance::chunkId));
        return ranked.size() <= limit ? ranked : List.copyOf(ranked.subList(0, limit));
    }

    /**
     * Relevance of one path.
     *
     * @param matchScore normalised entity match score
     * @param hops       relationship hops between the matched entity and the chunk's entity
     * @return relevance contribution of that path
     */
    private static double relevanceOf(double matchScore, int hops) {
        return matchScore / (1.0d + hops);
    }

    /**
     * Collects every path that reached one chunk.
     */
    private static final class ChunkAccumulator {

        private final String chunkId;
        private final Map<String, Double> scoreByEntity = new LinkedHashMap<>();
        private double best;
        private int bestHops;

        private ChunkAccumulator(String chunkId) {
            this.chunkId = chunkId;
            this.best = Double.NEGATIVE_INFINITY;
        }

        private void accept(GraphTraceRow row) {
            double relevance = relevanceOf(row.matchScore(), row.hops());
            // The same entity can reach a chunk through several paths; only its shortest one is evidence.
            scoreByEntity.merge(row.entityName(), relevance, Math::max);
            if (relevance > best) {
                best = relevance;
                bestHops = row.hops();
            }
        }

        private GraphChunkRelevance toRelevance() {
            List<String> names = scoreByEntity.entrySet().stream()
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed()
                            .thenComparing(Map.Entry.comparingByKey()))
                    .map(Map.Entry::getKey)
                    .limit(MAX_REPORTED_ENTITIES)
                    .toList();
            return new GraphChunkRelevance(chunkId, best, bestHops, names);
        }
    }
}
