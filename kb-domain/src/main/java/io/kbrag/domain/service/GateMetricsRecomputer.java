package io.kbrag.domain.service;

import io.kbrag.domain.model.GateCaseOutcome;
import io.kbrag.domain.model.GateComparison;
import io.kbrag.domain.model.GateCoreMetrics;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recomputes the two gated metrics of both sides of a dual run on the intersection of the cases both
 * sides actually judged, requirement section 4.7 "effective case definition".
 *
 * <p><b>Why an intersection and not the published metrics.</b> Each run reports its metrics over its own
 * effective set. If one side dropped a case as stale, the two denominators differ and the difference
 * between the published numbers mixes a quality change with a denominator artefact - which is exactly the
 * loophole the requirement closes by demanding a recomputation on the common set. Taking the intersection
 * also makes the tolerance meaningful: {@code 1/N} is only a sensible noise floor when both sides share
 * the same {@code N}.
 *
 * <p>A pure function over plain values, deliberately free of persistence and retrieval types.
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class GateMetricsRecomputer {

    /**
     * Recomputes both sides on their common cases.
     *
     * @param candidate candidate run's per case outcomes
     * @param baseline  released configuration's per case outcomes, {@code null} or empty on a first release
     * @return comparison over the intersection; {@code baseline} is {@code null} when no baseline was given
     */
    public GateComparison recompute(List<GateCaseOutcome> candidate, List<GateCaseOutcome> baseline) {
        Map<String, GateCaseOutcome> candidateByCase = byCaseId(candidate);
        if (CollectionUtils.isEmpty(baseline)) {
            List<GateCaseOutcome> all = new ArrayList<>(candidateByCase.values());
            return new GateComparison(metricsOf(all), null, all.size(), degradedCount(all, List.of()),
                    all.stream().map(GateCaseOutcome::caseId).toList());
        }
        Map<String, GateCaseOutcome> baselineByCase = byCaseId(baseline);

        List<GateCaseOutcome> candidateSide = new ArrayList<>();
        List<GateCaseOutcome> baselineSide = new ArrayList<>();
        List<String> caseIds = new ArrayList<>();
        // Iterating the candidate side preserves a deterministic order for the report drill down.
        for (Map.Entry<String, GateCaseOutcome> entry : candidateByCase.entrySet()) {
            GateCaseOutcome counterpart = baselineByCase.get(entry.getKey());
            if (counterpart == null) {
                continue;
            }
            caseIds.add(entry.getKey());
            candidateSide.add(entry.getValue());
            baselineSide.add(counterpart);
        }
        return new GateComparison(metricsOf(candidateSide), metricsOf(baselineSide), caseIds.size(),
                degradedCount(candidateSide, baselineSide), caseIds);
    }

    /**
     * Averages the per case values of one side.
     *
     * @param outcomes cases of one side, already restricted to the intersection
     * @return the two gated metrics, zero metrics for an empty side
     */
    private GateCoreMetrics metricsOf(List<GateCaseOutcome> outcomes) {
        if (CollectionUtils.isEmpty(outcomes)) {
            return new GateCoreMetrics(0.0d, 0.0d);
        }
        int hits = 0;
        double recallSum = 0.0d;
        for (GateCaseOutcome outcome : outcomes) {
            if (outcome.hit()) {
                hits++;
            }
            recallSum += outcome.recallFraction();
        }
        int count = outcomes.size();
        return new GateCoreMetrics((double) hits / count, recallSum / count);
    }

    /**
     * Counts the cases either side reported as degraded.
     *
     * <p>A case is counted once even when both sides degraded on it: the number answers "how many cases of
     * this comparison are untrustworthy", not "how many degradations occurred".
     *
     * @param candidateSide candidate cases of the intersection
     * @param baselineSide  baseline cases of the intersection, aligned by index
     * @return number of distinct cases that carried a degradation on at least one side
     */
    private int degradedCount(List<GateCaseOutcome> candidateSide, List<GateCaseOutcome> baselineSide) {
        int degraded = 0;
        for (int i = 0; i < candidateSide.size(); i++) {
            boolean candidateDegraded = candidateSide.get(i).degraded();
            boolean baselineDegraded = i < baselineSide.size() && baselineSide.get(i).degraded();
            if (candidateDegraded || baselineDegraded) {
                degraded++;
            }
        }
        return degraded;
    }

    private Map<String, GateCaseOutcome> byCaseId(List<GateCaseOutcome> outcomes) {
        Map<String, GateCaseOutcome> byCase = new LinkedHashMap<>();
        if (CollectionUtils.isEmpty(outcomes)) {
            return byCase;
        }
        for (GateCaseOutcome outcome : outcomes) {
            if (outcome != null && outcome.caseId() != null) {
                byCase.putIfAbsent(outcome.caseId(), outcome);
            }
        }
        return byCase;
    }
}
