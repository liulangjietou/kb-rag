package io.kbrag.domain.model;

/**
 * One case's result as the release gate reads it back from a finished evaluation run.
 *
 * <p>Flat and free of persistence types so the intersection recomputation stays a pure function: the gate
 * has to recompute its metrics on the cases <b>both</b> runs actually judged, and doing that arithmetic
 * over plain values is what makes it testable with hand picked numbers instead of two mocked runs.
 *
 * @param caseId             case business id, the key the intersection is taken on
 * @param hit                {@code true} when the case was answered within the top {@code K}
 * @param evidenceHitCount   evidences covered within the top {@code K}
 * @param evidenceTotalCount evidences the case declares
 * @param degraded           {@code true} when the case still carried a degradation marker after retries
 *
 * @author owlzhangfq@gmail.com
 */
public record GateCaseOutcome(
        String caseId,
        boolean hit,
        int evidenceHitCount,
        int evidenceTotalCount,
        boolean degraded) {

    /**
     * Per case {@code Recall@K} contribution.
     *
     * @return covered over declared, {@code 0} when the case declares no evidence
     */
    public double recallFraction() {
        return evidenceTotalCount == 0 ? 0.0d : (double) evidenceHitCount / evidenceTotalCount;
    }
}
