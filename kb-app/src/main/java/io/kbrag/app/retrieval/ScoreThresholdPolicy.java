package io.kbrag.app.retrieval;

import io.kbrag.domain.enums.FusionMode;
import io.kbrag.domain.enums.RetrievalSource;
import io.kbrag.domain.enums.ScoreType;
import lombok.Getter;
import lombok.ToString;
import org.springframework.stereotype.Component;

/**
 * Decides which score a threshold acts on and which score a node reports.
 *
 * <p>Two questions that look like one. Ordering and filtering do not have to use the same score: the
 * pipeline orders by whatever ranks best, but an absolute threshold may only be compared against a
 * score whose scale is stable across queries. Resolving both here keeps the rule auditable and keeps
 * the reported {@code score_type} honest, which matters because a caller that filters on a number
 * has no other way to know what that number meant.
 *
 * <p>Target selection, in order:
 * <ol>
 *   <li>the rerank stage ran, so the cross encoder relevance is available and wins;</li>
 *   <li>otherwise the vector route ran, so the normalised cosine similarity is used;</li>
 *   <li>otherwise only BM25 ran, whose raw score is unbounded, and the threshold is dropped with a
 *       {@code threshold_inactive} marker rather than being applied to something incomparable.</li>
 * </ol>
 *
 * <p>Reported score. When a threshold is active the node reports the score it was filtered by, so the
 * filter can be verified from the response alone. With no threshold there is nothing to verify and the
 * node reports the score that ordered it: the fusion score on a dual route search, the raw BM25 score
 * on a single route one.
 */
@Component
public class ScoreThresholdPolicy {

    /** Score reported for a candidate the active target has no value for. */
    private static final double MISSING_SCORE = 0.0d;

    /**
     * Resolves the threshold decision of one search.
     *
     * @param requestedThreshold caller supplied threshold, {@code null} means no filtering
     * @param rerankApplied      {@code true} when the rerank stage produced scores
     * @param vectorRouteRan     {@code true} when the vector route contributed candidates
     * @return decision consumed by the filter and by the node builder
     */
    public ThresholdDecision decide(Double requestedThreshold, boolean rerankApplied, boolean vectorRouteRan) {
        ThresholdTarget target = resolveTarget(rerankApplied, vectorRouteRan);
        boolean requested = requestedThreshold != null;
        boolean active = requested && target != ThresholdTarget.NONE;
        return new ThresholdDecision(target, active ? requestedThreshold : null, requested && !active);
    }

    /**
     * Reads the score the threshold compares against.
     *
     * @param candidate candidate under test
     * @param decision  threshold decision of this search
     * @return comparable score, {@code null} when the target has no value for this candidate
     */
    public Double thresholdScore(RetrievalCandidate candidate, ThresholdDecision decision) {
        if (decision.getTarget() == ThresholdTarget.RERANK) {
            return candidate.getRerankScore();
        }
        if (decision.getTarget() == ThresholdTarget.COSINE) {
            return candidate.getFused().routeScore(RetrievalSource.VECTOR);
        }
        return null;
    }

    /**
     * Builds the score and the score type a node reports.
     *
     * @param candidate  candidate being turned into a node
     * @param decision   threshold decision of this search
     * @param fusionMode strategy that produced the fusion score
     * @return reported score together with its nature
     */
    public ReportedScore report(RetrievalCandidate candidate, ThresholdDecision decision, FusionMode fusionMode) {
        if (candidate.getRerankScore() != null) {
            return new ReportedScore(candidate.getRerankScore(), ScoreType.RERANK);
        }
        if (decision.isActive() && decision.getTarget() == ThresholdTarget.COSINE) {
            Double cosine = candidate.getFused().routeScore(RetrievalSource.VECTOR);
            return new ReportedScore(cosine == null ? MISSING_SCORE : cosine, ScoreType.COSINE);
        }
        if (decision.getTarget() == ThresholdTarget.NONE) {
            // No rerank and no vector route: fusing one list only relabels the BM25 ranking, so the
            // raw score carries strictly more information than the fusion score would.
            Double bm25 = candidate.getFused().routeScore(RetrievalSource.BM25);
            return new ReportedScore(bm25 == null ? MISSING_SCORE : bm25, ScoreType.BM25_RANK);
        }
        ScoreType fusedType = fusionMode == FusionMode.WEIGHTED
                ? ScoreType.FUSED_WEIGHTED : ScoreType.FUSED_RRF;
        return new ReportedScore(candidate.getFused().getFusedScore(), fusedType);
    }

    private ThresholdTarget resolveTarget(boolean rerankApplied, boolean vectorRouteRan) {
        if (rerankApplied) {
            return ThresholdTarget.RERANK;
        }
        return vectorRouteRan ? ThresholdTarget.COSINE : ThresholdTarget.NONE;
    }

    /**
     * Outcome of the target selection for one search.
     */
    @Getter
    @ToString
    public static final class ThresholdDecision {

        /** Score the threshold acts on. */
        private final ThresholdTarget target;

        /** Effective threshold, {@code null} when no filtering happens. */
        private final Double threshold;

        /** {@code true} when a threshold was requested but no comparable score exists. */
        private final boolean inactive;

        ThresholdDecision(ThresholdTarget target, Double threshold, boolean inactive) {
            this.target = target;
            this.threshold = threshold;
            this.inactive = inactive;
        }

        /**
         * Tells whether candidates are actually filtered.
         *
         * @return {@code true} when a threshold is applied
         */
        public boolean isActive() {
            return threshold != null;
        }

        /**
         * Literal exposed through the {@code applied} block, describing what the threshold acted on.
         *
         * @return score type literal, {@code none} when nothing was filtered
         */
        public String appliedOn() {
            return isActive() ? target.code() : ThresholdTarget.NONE.code();
        }
    }

    /**
     * Score a node reports together with its nature.
     */
    @Getter
    @ToString
    public static final class ReportedScore {

        /** Value placed in {@code score}. */
        private final double value;

        /** Value placed in {@code score_type}. */
        private final ScoreType type;

        ReportedScore(double value, ScoreType type) {
            this.value = value;
            this.type = type;
        }
    }
}
