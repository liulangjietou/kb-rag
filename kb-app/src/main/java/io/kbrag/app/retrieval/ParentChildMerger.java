package io.kbrag.app.retrieval;

import io.kbrag.domain.model.FusedChunk;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a ranked list of child candidates into the units the caller receives.
 *
 * <p>Two responsibilities that belong together because both depend on the same fact: with two level
 * splitting the number of returned units is smaller than the number of candidates, and by an amount
 * nobody knows before looking at the data.
 *
 * <p><b>Candidate budget.</b> Reranking is billed per candidate and merging collapses several
 * candidates into one unit, so taking a fixed number of children would either overpay when they are
 * spread across many parents or starve the merge when they cluster inside a few. The budget therefore
 * walks the coarse ranking and stops as soon as enough distinct parents are represented, capped by
 * the rerank limit. The parent target is a multiple of {@code top_n} rather than {@code top_n} itself
 * because the threshold and the rerank reordering both discard units afterwards.
 *
 * <p><b>Merge.</b> Children are grouped by parent and each group keeps its members ordered, so the
 * response can show which passages inside a parent actually matched. Group order follows the best
 * member, which preserves the ranking the previous stage produced.
 */
@Slf4j
@Component
public class ParentChildMerger {

    /**
     * Number of coarse candidates worth carrying into the rerank and merge stages.
     *
     * @param fused            fused candidates ordered by descending fusion score
     * @param parentIdByChunk  parent chunk id per candidate chunk id, missing or {@code null} value
     *                         meaning the candidate is its own unit
     * @param parentTarget     number of distinct units the merge should be able to produce
     * @param maxCandidates    hard cap imposed by the rerank stage
     * @return number of leading candidates to keep
     */
    public int candidateCount(List<FusedChunk> fused, Map<String, String> parentIdByChunk,
                              int parentTarget, int maxCandidates) {
        if (CollectionUtils.isEmpty(fused)) {
            return 0;
        }
        Set<String> units = new LinkedHashSet<>();
        int taken = 0;
        for (FusedChunk candidate : fused) {
            if (taken >= maxCandidates) {
                break;
            }
            taken++;
            units.add(unitKey(candidate.getChunkId(), parentIdByChunk));
            if (units.size() >= parentTarget) {
                break;
            }
        }
        return taken;
    }

    /**
     * Groups ranked candidates into units.
     *
     * @param ranked candidates ordered by descending ordering score
     * @return units ordered by descending unit score
     */
    public List<RetrievalUnit> merge(List<RetrievalCandidate> ranked) {
        Map<String, List<RetrievalCandidate>> byUnit = new LinkedHashMap<>();
        Map<String, Boolean> parentFlags = new LinkedHashMap<>();
        for (RetrievalCandidate candidate : ranked) {
            String unitKey = candidate.unitKey();
            byUnit.computeIfAbsent(unitKey, key -> new ArrayList<>()).add(candidate);
            parentFlags.putIfAbsent(unitKey, !unitKey.equals(candidate.chunkId()));
        }

        List<RetrievalUnit> units = new ArrayList<>(byUnit.size());
        for (Map.Entry<String, List<RetrievalCandidate>> entry : byUnit.entrySet()) {
            List<RetrievalCandidate> members = new ArrayList<>(entry.getValue());
            members.sort(Comparator.comparingDouble(RetrievalCandidate::orderingScore).reversed()
                    .thenComparing(RetrievalCandidate::chunkId));
            units.add(new RetrievalUnit(entry.getKey(), parentFlags.get(entry.getKey()), members));
        }
        units.sort(Comparator.comparingDouble(RetrievalUnit::score).reversed()
                .thenComparing(RetrievalUnit::getUnitId));
        return units;
    }

    /**
     * Parent target of one search.
     *
     * @param topN   number of returned units
     * @param factor multiplier applied to {@code topN}
     * @param floor  lower bound protecting a small {@code topN}
     * @return number of distinct units the merge should be able to produce
     */
    public int parentTarget(int topN, int factor, int floor) {
        return Math.max(topN * factor, floor);
    }

    private String unitKey(String chunkId, Map<String, String> parentIdByChunk) {
        String parentId = parentIdByChunk.get(chunkId);
        return parentId == null || parentId.isBlank() ? chunkId : parentId;
    }
}
