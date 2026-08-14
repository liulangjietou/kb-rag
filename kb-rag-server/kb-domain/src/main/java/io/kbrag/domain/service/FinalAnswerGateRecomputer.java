package io.kbrag.domain.service;

import io.kbrag.domain.model.FinalAnswerCaseOutcome;
import io.kbrag.domain.model.FinalAnswerGateComparison;
import io.kbrag.domain.model.FinalAnswerMetrics;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recomputes final-answer metrics on the common requested cases of a gate dual run.
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class FinalAnswerGateRecomputer {

    private final FinalAnswerMetricsCalculator calculator;

    public FinalAnswerGateRecomputer(FinalAnswerMetricsCalculator calculator) {
        this.calculator = calculator;
    }

    /**
     * Recomputes the candidate and optional baseline on a shared denominator.
     *
     * @param candidate candidate case outcomes
     * @param baseline baseline case outcomes, empty on a first release
     * @return common-case answer comparison
     */
    public FinalAnswerGateComparison recompute(List<FinalAnswerCaseOutcome> candidate,
                                               List<FinalAnswerCaseOutcome> baseline) {
        Map<String, FinalAnswerCaseOutcome> candidateByCase = requestedByCase(candidate);
        if (CollectionUtils.isEmpty(baseline)) {
            List<FinalAnswerCaseOutcome> requested = new ArrayList<>(candidateByCase.values());
            FinalAnswerMetrics metrics = calculator.aggregate(requested);
            return new FinalAnswerGateComparison(metrics, null, metrics.evaluatedCases(),
                    metrics.judgeFailedCases(), degradedCount(requested, List.of()),
                    judgedCaseIds(requested, List.of()));
        }
        Map<String, FinalAnswerCaseOutcome> baselineByCase = requestedByCase(baseline);
        List<FinalAnswerCaseOutcome> candidateSide = new ArrayList<>();
        List<FinalAnswerCaseOutcome> baselineSide = new ArrayList<>();
        int requested = 0;
        for (Map.Entry<String, FinalAnswerCaseOutcome> entry : candidateByCase.entrySet()) {
            FinalAnswerCaseOutcome counterpart = baselineByCase.get(entry.getKey());
            if (counterpart != null) {
                requested++;
                if (entry.getValue().judged() && counterpart.judged()) {
                    candidateSide.add(entry.getValue());
                    baselineSide.add(counterpart);
                }
            }
        }
        FinalAnswerMetrics candidateMetrics = calculator.aggregate(candidateSide);
        FinalAnswerMetrics baselineMetrics = calculator.aggregate(baselineSide);
        List<String> caseIds = candidateSide.stream().map(FinalAnswerCaseOutcome::caseId).toList();
        int failed = requested - caseIds.size();
        return new FinalAnswerGateComparison(candidateMetrics, baselineMetrics, caseIds.size(), failed,
                degradedCount(candidateSide, baselineSide), caseIds);
    }

    private Map<String, FinalAnswerCaseOutcome> requestedByCase(List<FinalAnswerCaseOutcome> outcomes) {
        Map<String, FinalAnswerCaseOutcome> byCase = new LinkedHashMap<>();
        if (CollectionUtils.isEmpty(outcomes)) {
            return byCase;
        }
        for (FinalAnswerCaseOutcome outcome : outcomes) {
            if (outcome != null && outcome.caseId() != null && outcome.judgeRequested()) {
                byCase.putIfAbsent(outcome.caseId(), outcome);
            }
        }
        return byCase;
    }

    private List<String> judgedCaseIds(List<FinalAnswerCaseOutcome> candidate,
                                       List<FinalAnswerCaseOutcome> baseline) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < candidate.size(); i++) {
            boolean baselineJudged = baseline.isEmpty() || i < baseline.size() && baseline.get(i).judged();
            if (candidate.get(i).judged() && baselineJudged) {
                ids.add(candidate.get(i).caseId());
            }
        }
        return ids;
    }

    private int degradedCount(List<FinalAnswerCaseOutcome> candidate,
                              List<FinalAnswerCaseOutcome> baseline) {
        int degraded = 0;
        for (int i = 0; i < candidate.size(); i++) {
            if (candidate.get(i).degraded() || i < baseline.size() && baseline.get(i).degraded()) {
                degraded++;
            }
        }
        return degraded;
    }
}
