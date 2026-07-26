package io.kbrag.domain.service;

import io.kbrag.domain.model.CaseJudgment;
import io.kbrag.domain.model.ConfidenceInterval;
import io.kbrag.domain.model.EvalMetricsAtK;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Aggregates {@link CaseJudgment} rows of one group into {@link EvalMetricsAtK}, requirement section
 * 4.6.
 *
 * <p><b>Recall@K</b> and <b>Hit Rate</b> both average a per case value, but their confidence intervals
 * are pooled differently on purpose. Hit Rate is already a case level Bernoulli proportion (the case
 * either has a hit or it does not), so its interval is the exact Wilson interval of "cases hit" over
 * "cases judged". Recall@K is a per case fraction (evidences covered over evidences declared), which
 * is not itself a single proportion; its interval is instead computed on the pooled evidence level
 * counts across every case of the group ("evidences covered" over "evidences declared", summed) - an
 * approximation, acceptable only because every confidence interval in this subsystem is display only
 * and never gates a decision.
 *
 * <p><b>MRR</b> reuses the same {@code hit}/{@code hitRank} pair the hit judgment already produced,
 * rather than a second, independent "first relevant rank" definition: the two would otherwise answer
 * subtly different questions and the report page would show two numbers that look related but are not
 * reconcilable from each other.
 *
 * <p><b>Precision@K</b> and <b>NDCG@K</b> read {@link CaseJudgment#relevancePerRank()}, a looser, per
 * item relevance test (does this single ranked unit carry any positive evidence overlap at all) than
 * the aggregate hit condition; that is what standard rank quality metrics ask for, as opposed to "was
 * the case answered".
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class EvalMetricsCalculator {

    /** Two sided 95% z-score. */
    private static final double Z_95 = 1.959963985d;

    private static final EvalMetricsAtK EMPTY = new EvalMetricsAtK(0, 0, 0, 0, 0,
            new ConfidenceInterval(0, 0), new ConfidenceInterval(0, 0));

    /**
     * Aggregates one group of case judgments at one {@code K}.
     *
     * @param judgments case judgments of the group, empty yields the zero metrics
     * @param k         evaluation {@code K}, the length every {@code relevancePerRank} list carries
     * @return aggregated metrics with their display only confidence intervals
     */
    public EvalMetricsAtK aggregate(List<CaseJudgment> judgments, int k) {
        if (CollectionUtils.isEmpty(judgments)) {
            return EMPTY;
        }
        int caseCount = judgments.size();
        int hitCases = 0;
        int evidenceHits = 0;
        int evidenceTotal = 0;
        double recallSum = 0;
        double precisionSum = 0;
        double mrrSum = 0;
        double ndcgSum = 0;
        for (CaseJudgment judgment : judgments) {
            if (judgment.hit()) {
                hitCases++;
                mrrSum += 1.0d / judgment.hitRank();
            }
            evidenceHits += judgment.evidenceHitCount();
            evidenceTotal += judgment.evidenceTotalCount();
            recallSum += judgment.recallFraction();
            precisionSum += precisionOf(judgment, k);
            ndcgSum += ndcgOf(judgment, k);
        }
        double recall = recallSum / caseCount;
        double precision = precisionSum / caseCount;
        double hitRate = (double) hitCases / caseCount;
        double mrr = mrrSum / caseCount;
        double ndcg = ndcgSum / caseCount;
        return new EvalMetricsAtK(recall, precision, hitRate, mrr, ndcg,
                wilson(evidenceHits, evidenceTotal), wilson(hitCases, caseCount));
    }

    private double precisionOf(CaseJudgment judgment, int k) {
        if (k <= 0 || CollectionUtils.isEmpty(judgment.relevancePerRank())) {
            return 0.0d;
        }
        int relevant = 0;
        for (Boolean flag : judgment.relevancePerRank()) {
            if (Boolean.TRUE.equals(flag)) {
                relevant++;
            }
        }
        return (double) relevant / k;
    }

    /**
     * Binary relevance {@code NDCG@K}.
     *
     * @param judgment case judgment
     * @param k        evaluation {@code K}
     * @return {@code DCG@K / IDCG@K}, {@code 0} when the case declares no relevant unit at all
     */
    private double ndcgOf(CaseJudgment judgment, int k) {
        List<Boolean> relevance = judgment.relevancePerRank();
        if (k <= 0 || CollectionUtils.isEmpty(relevance)) {
            return 0.0d;
        }
        double dcg = 0.0d;
        int limit = Math.min(k, relevance.size());
        for (int i = 0; i < limit; i++) {
            if (Boolean.TRUE.equals(relevance.get(i))) {
                // Rank is one based, i is zero based, so the position term is log2(rank + 1) = log2(i + 2).
                dcg += 1.0d / log2(i + 2);
            }
        }
        int idealCount = Math.min(judgment.idealRelevantCount(), k);
        if (idealCount <= 0) {
            return 0.0d;
        }
        double idcg = 0.0d;
        for (int i = 0; i < idealCount; i++) {
            idcg += 1.0d / log2(i + 2);
        }
        return idcg == 0.0d ? 0.0d : dcg / idcg;
    }

    private double log2(int value) {
        return Math.log(value) / Math.log(2);
    }

    /**
     * Two sided 95% Wilson score interval of a proportion, more stable than the normal approximation
     * on the small samples an evaluation data set typically has.
     *
     * @param successes successes observed, {@code x}
     * @param trials    trials observed, {@code n}
     * @return interval clamped to {@code [0,1]}, {@code (0,0)} when there were no trials
     */
    private ConfidenceInterval wilson(int successes, int trials) {
        if (trials <= 0) {
            return new ConfidenceInterval(0.0d, 0.0d);
        }
        double n = trials;
        double p = successes / n;
        double z2 = Z_95 * Z_95;
        double denominator = 1 + z2 / n;
        double center = p + z2 / (2 * n);
        double margin = Z_95 * Math.sqrt(p * (1 - p) / n + z2 / (4 * n * n));
        double low = (center - margin) / denominator;
        double high = (center + margin) / denominator;
        return new ConfidenceInterval(clamp(low), clamp(high));
    }

    private double clamp(double value) {
        return Math.min(1.0d, Math.max(0.0d, value));
    }
}
