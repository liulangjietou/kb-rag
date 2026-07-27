package io.kbrag.domain.service;

import io.kbrag.domain.enums.FusionMode;
import io.kbrag.domain.model.FusedChunk;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Merges the per base rankings of one call into a single ordered candidate set, requirement section 4.7
 * "cross base fusion rules" and section 4.4 "fixed order of the three fusion layers".
 *
 * <p><b>Ranks, never scores.</b> Two knowledge bases may be embedded by different models and are indexed
 * with different corpus statistics, so their fusion scores share neither scale nor meaning; adding or
 * comparing them would produce an ordering that looks principled and is not. The position a candidate
 * reached <em>inside its own base</em> is the one comparable fact across bases, which is why this layer
 * is reciprocal rank fusion over in base ranks and why the damping constant is the same {@code rrf_k}
 * the in base layer used.
 *
 * <p><b>The cross base score replaces the in base one.</b> {@link FusedChunk#getFusedScore()} is what the
 * later stages order by, so leaving the in base value in place would let an unranked comparison back in
 * through the ordering. The per route ranks and raw scores are carried through untouched, so the debug
 * page still shows what each route contributed inside its base.
 *
 * <p>The result is ordered by descending fusion score with the chunk id as tie breaker: candidates that
 * hold the same position in two different bases are genuinely tied here, and a stable tie break is what
 * keeps an evaluation run reproducible.
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class CrossKbRrfFusion {

    /**
     * Merges the per base rankings.
     *
     * @param rankedByKb in base ranking per knowledge base id, each already cut to its quota
     * @param rrfK       damping constant, the call's {@code rrf_k}
     * @return unified candidate order carrying the cross base fusion score
     */
    public List<FusedChunk> merge(Map<String, List<FusedChunk>> rankedByKb, int rrfK) {
        if (MapUtils.isEmpty(rankedByKb)) {
            return List.of();
        }
        List<FusedChunk> merged = new ArrayList<>();
        for (List<FusedChunk> ranking : rankedByKb.values()) {
            if (CollectionUtils.isEmpty(ranking)) {
                continue;
            }
            for (int i = 0; i < ranking.size(); i++) {
                merged.add(withCrossKbScore(ranking.get(i), 1.0d / (rrfK + i + 1)));
            }
        }
        merged.sort(Comparator.comparingDouble(FusedChunk::getFusedScore).reversed()
                .thenComparing(FusedChunk::getChunkId));
        return merged;
    }

    /**
     * Rebuilds one candidate with the cross base score in place of the in base one.
     *
     * @param candidate in base candidate
     * @param score     cross base reciprocal rank score
     * @return candidate carrying the cross base score and the untouched route evidence
     */
    private FusedChunk withCrossKbScore(FusedChunk candidate, double score) {
        return FusedChunk.builder()
                .chunkId(candidate.getChunkId())
                .fusedScore(score)
                .fusionMode(FusionMode.RRF)
                .primarySource(candidate.getPrimarySource())
                .routeRanks(candidate.getRouteRanks())
                .routeScores(candidate.getRouteScores())
                .normalizedScores(candidate.getNormalizedScores())
                .build();
    }
}
