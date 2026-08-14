package io.kbrag.domain.service;

import io.kbrag.domain.model.FinalAnswerCaseOutcome;
import io.kbrag.domain.model.FinalAnswerMetrics;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Aggregates structured final-answer judgments without persistence or provider dependencies.
 *
 * <p>Judge failures are counted but never converted to zero-valued scores. Mixing transport failure with
 * answer quality would make a provider outage look like a model regression and could incorrectly block a
 * release for content that was never judged.
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class FinalAnswerMetricsCalculator {

    /**
     * Aggregates answer outcomes.
     *
     * @param outcomes case outcomes of one run or a gate intersection
     * @return final-answer metrics; all means are zero when no case was judged
     */
    public FinalAnswerMetrics aggregate(List<FinalAnswerCaseOutcome> outcomes) {
        if (CollectionUtils.isEmpty(outcomes)) {
            return empty();
        }
        List<FinalAnswerCaseOutcome> judged = outcomes.stream()
                .filter(FinalAnswerCaseOutcome::judged)
                .toList();
        int requested = (int) outcomes.stream().filter(FinalAnswerCaseOutcome::judgeRequested).count();
        int failed = requested - judged.size();
        if (judged.isEmpty()) {
            return new FinalAnswerMetrics(0.0d, 0.0d, 0.0d, 0.0d, 0.0d, 0.0d,
                    0.0d, 0, Math.max(0, failed), 0);
        }
        int count = judged.size();
        double score = 0.0d;
        double correctness = 0.0d;
        double faithfulness = 0.0d;
        double completeness = 0.0d;
        double citationCorrectness = 0.0d;
        double citationCompleteness = 0.0d;
        int refusalCorrect = 0;
        List<Integer> latencies = new ArrayList<>(count);
        for (FinalAnswerCaseOutcome outcome : judged) {
            score += outcome.score();
            correctness += outcome.correctness();
            faithfulness += outcome.faithfulness();
            completeness += outcome.completeness();
            citationCorrectness += outcome.citationCorrectness();
            citationCompleteness += outcome.citationCompleteness();
            if (Boolean.TRUE.equals(outcome.refusalCorrect())) {
                refusalCorrect++;
            }
            if (outcome.generationLatencyMs() != null) {
                latencies.add(outcome.generationLatencyMs());
            }
        }
        return new FinalAnswerMetrics(score / count, correctness / count, faithfulness / count,
                completeness / count, citationCorrectness / count, citationCompleteness / count,
                (double) refusalCorrect / count, count, Math.max(0, failed), percentile95(latencies));
    }

    private int percentile95(List<Integer> values) {
        if (CollectionUtils.isEmpty(values)) {
            return 0;
        }
        List<Integer> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int rank = (int) Math.ceil(sorted.size() * 0.95d);
        return sorted.get(Math.max(0, rank - 1));
    }

    private FinalAnswerMetrics empty() {
        return new FinalAnswerMetrics(0.0d, 0.0d, 0.0d, 0.0d, 0.0d, 0.0d,
                0.0d, 0, 0, 0);
    }
}
