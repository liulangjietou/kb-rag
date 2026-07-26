package io.kbrag.domain.model;

import java.util.List;

/**
 * Judgment of one evaluation case against the top {@code K} of one run, the input of
 * {@link io.kbrag.domain.service.EvalMetricsCalculator}.
 *
 * <p>Kept independent from {@code RetrievalNodeView} on purpose: the calculator is a pure function
 * over these plain values, which is what lets its arithmetic be unit tested with hand picked numbers
 * instead of a mocked retrieval pipeline. Turning a run's actual nodes into this shape is the
 * responsibility of the evaluation runner, one layer up.
 *
 * @param hit                {@code true} when the aggregate coverage (span case) or the anchored
 *                           document (document case) was found within the top {@code K}
 * @param hitRank            one based rank of the smallest prefix of the top {@code K} at which the hit
 *                           condition first held, {@code null} when {@link #hit} is {@code false}
 * @param evidenceHitCount   evidences (or relevant documents) covered within the top {@code K}
 * @param evidenceTotalCount total evidences (or relevant documents) the case declares
 * @param relevancePerRank   per rank relevance flag, exactly {@code K} entries, padded with
 *                           {@code false} when fewer than {@code K} candidates were returned; an
 *                           entry is {@code true} when that single ranked unit, on its own, carries
 *                           any positive overlap with an evidence span or matches an evidence document
 * @param idealRelevantCount upper bound of relevant units a perfect ranking could show, used as the
 *                           ideal ranking length of {@code NDCG@K}; equal to {@link #evidenceTotalCount}
 *
 * @author owlzhangfq@gmail.com
 */
public record CaseJudgment(
        boolean hit,
        Integer hitRank,
        int evidenceHitCount,
        int evidenceTotalCount,
        List<Boolean> relevancePerRank,
        int idealRelevantCount) {

    /**
     * Fraction of evidences (or relevant documents) covered within the top {@code K}.
     *
     * @return {@code evidenceHitCount / evidenceTotalCount}, {@code 0} when the case declares none
     */
    public double recallFraction() {
        return evidenceTotalCount == 0 ? 0.0d : (double) evidenceHitCount / evidenceTotalCount;
    }
}
