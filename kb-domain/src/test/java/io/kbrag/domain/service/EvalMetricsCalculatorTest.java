package io.kbrag.domain.service;

import io.kbrag.domain.model.CaseJudgment;
import io.kbrag.domain.model.EvalMetricsAtK;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers metric aggregation of requirement section 4.6 with hand computed values: Recall@K,
 * Precision@K, Hit Rate, MRR, NDCG@K and the Wilson 95% confidence interval.
 *
 * @author owlzhangfq@gmail.com
 */
class EvalMetricsCalculatorTest {

    private static final double DELTA = 1e-6;

    private final EvalMetricsCalculator calculator = new EvalMetricsCalculator();

    @Test
    void shouldAggregateTwoCasesWithHandComputedValues() {
        // Case A: hit at rank 1, both evidences eventually covered, relevant at ranks 1 and 3.
        CaseJudgment caseA = new CaseJudgment(true, 1, 2, 2,
                List.of(true, false, true), 2);
        // Case B: never hit.
        CaseJudgment caseB = new CaseJudgment(false, null, 0, 2,
                List.of(false, false, false), 2);

        EvalMetricsAtK metrics = calculator.aggregate(List.of(caseA, caseB), 3);

        assertEquals(0.5d, metrics.recall(), DELTA);
        assertEquals(0.333333333333d, metrics.precision(), 1e-9);
        assertEquals(0.5d, metrics.hitRate(), DELTA);
        assertEquals(0.5d, metrics.mrr(), DELTA);
        assertEquals(0.459860394574d, metrics.ndcg(), 1e-9);

        // Wilson interval of the pooled evidence hits: 2 covered out of 4 evidences declared.
        assertEquals(0.150038989110d, metrics.recallCi().low(), 1e-9);
        assertEquals(0.849961010890d, metrics.recallCi().high(), 1e-9);
        // Wilson interval of the case level hit rate: 1 hit out of 2 cases.
        assertEquals(0.094531205702d, metrics.hitRateCi().low(), 1e-9);
        assertEquals(0.905468794298d, metrics.hitRateCi().high(), 1e-9);
    }

    @Test
    void shouldGiveAPerfectScoreWhenEveryCaseIsFullyRecalledAtRankOne() {
        CaseJudgment perfect = new CaseJudgment(true, 1, 1, 1, List.of(true), 1);

        EvalMetricsAtK metrics = calculator.aggregate(List.of(perfect, perfect), 1);

        assertEquals(1.0d, metrics.recall(), DELTA);
        assertEquals(1.0d, metrics.precision(), DELTA);
        assertEquals(1.0d, metrics.hitRate(), DELTA);
        assertEquals(1.0d, metrics.mrr(), DELTA);
        assertEquals(1.0d, metrics.ndcg(), DELTA);
    }

    @Test
    void shouldReturnTheZeroMetricsForAnEmptyGroup() {
        EvalMetricsAtK metrics = calculator.aggregate(List.of(), 5);

        assertEquals(0.0d, metrics.recall());
        assertEquals(0.0d, metrics.precision());
        assertEquals(0.0d, metrics.hitRate());
        assertEquals(0.0d, metrics.mrr());
        assertEquals(0.0d, metrics.ndcg());
    }

    @Test
    void shouldMatchTheTextbookWilsonIntervalForEightOutOfTen() {
        CaseJudgment hit = new CaseJudgment(true, 1, 1, 1, List.of(true), 1);
        CaseJudgment miss = new CaseJudgment(false, null, 0, 1, List.of(false), 1);
        List<CaseJudgment> judgments = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            judgments.add(hit);
        }
        for (int i = 0; i < 2; i++) {
            judgments.add(miss);
        }

        EvalMetricsAtK metrics = calculator.aggregate(judgments, 1);

        // Wilson 95% interval of 8/10, cross checked against a reference implementation.
        assertEquals(0.490162471465d, metrics.hitRateCi().low(), 1e-9);
        assertEquals(0.943317848561d, metrics.hitRateCi().high(), 1e-9);
    }

    @Test
    void shouldGiveTheWidestIntervalToTheSmallestSample() {
        CaseJudgment miss = new CaseJudgment(false, null, 0, 1, List.of(false), 1);

        EvalMetricsAtK metrics = calculator.aggregate(List.of(miss, miss, miss, miss, miss), 1);

        assertEquals(0.0d, metrics.hitRateCi().low(), DELTA);
        assertEquals(0.434482464898d, metrics.hitRateCi().high(), 1e-9);
    }
}
