package io.kbrag.app.retrieval;

import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.enums.FusionMode;
import io.kbrag.domain.enums.RerankMode;
import io.kbrag.domain.model.FusionParams;
import io.kbrag.domain.model.KbRetrievalConfig;
import lombok.Getter;
import lombok.ToString;

/**
 * Effective parameters of one retrieval call.
 *
 * <p>Collapses the three configuration layers in one place, highest priority first: the request, then
 * the knowledge base defaults, then the deployment defaults. Doing it once and up front is what lets
 * every later stage read a plain value and keeps the precedence rule from being re-implemented, and
 * differently, at each use site.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@ToString
public final class RetrievalSettings {

    /** Candidates recalled per route. */
    private final int recallTopK;

    /** Number of returned units. */
    private final int topN;

    /** Absolute score threshold, {@code null} disables filtering. */
    private final Double scoreThreshold;

    /** Validated fusion parameters. */
    private final FusionParams fusion;

    /** {@code true} when the rerank stage should run. */
    private final boolean rerankEnabled;

    /** Resolved rerank ordering mode, the M14 contract section 5. */
    private final RerankMode rerankMode;

    /** Resolved semantic weight of the {@code hybrid} rerank mode, within {@code [0,1]}. */
    private final double rerankWSemantic;

    /** {@code true} when the query rewrite stage should run. */
    private final boolean rewriteEnabled;

    private RetrievalSettings(int recallTopK, int topN, Double scoreThreshold, FusionParams fusion,
                              boolean rerankEnabled, RerankMode rerankMode, double rerankWSemantic,
                              boolean rewriteEnabled) {
        this.recallTopK = recallTopK;
        this.topN = topN;
        this.scoreThreshold = scoreThreshold;
        this.fusion = fusion;
        this.rerankEnabled = rerankEnabled;
        this.rerankMode = rerankMode;
        this.rerankWSemantic = rerankWSemantic;
        this.rewriteEnabled = rewriteEnabled;
    }

    /**
     * Resolves the effective parameters.
     *
     * @param command          request level parameters, every field optional
     * @param kbConfig         knowledge base level defaults, {@code null} when the column is empty
     * @param retrievalDefaults deployment level defaults
     * @return effective parameters
     */
    public static RetrievalSettings resolve(RetrievalCommand command,
                                            KbRetrievalConfig kbConfig,
                                            KbProperties.Retrieval retrievalDefaults) {
        KbRetrievalConfig kb = kbConfig == null ? new KbRetrievalConfig() : kbConfig;

        int recallTopK = clamp(firstNonNull(command.getRecallTopK(), kb.getRecallTopK()),
                retrievalDefaults.getDefaultRecallTopK(), retrievalDefaults.getMaxRecallTopK());
        int topN = clamp(firstNonNull(command.getTopN(), kb.getTopN()),
                retrievalDefaults.getDefaultTopN(), retrievalDefaults.getMaxTopN());
        Double threshold = firstNonNull(command.getScoreThreshold(), kb.getScoreThreshold());

        FusionMode mode = FusionMode.from(firstNonNull(command.getFusionMode(),
                firstNonNull(kb.getFusionMode(), retrievalDefaults.getFusionMode())));
        int rrfK = firstNonNull(command.getRrfK(), firstNonNull(kb.getRrfK(), retrievalDefaults.getRrfK()));
        double wVec = firstNonNull(command.getWVec(), firstNonNull(kb.getWVec(), retrievalDefaults.getWVec()));

        boolean rerank = firstNonNull(command.getRerankEnabled(),
                firstNonNull(kb.getRerankEnabled(), retrievalDefaults.isRerankEnabled()));
        RerankMode rerankMode = RerankMode.from(firstNonNull(command.getRerankMode(),
                firstNonNull(kb.getRerankMode(), retrievalDefaults.getRerankMode())));
        double rerankWSemantic = clampWeight(firstNonNull(command.getRerankWSemantic(),
                firstNonNull(kb.getRerankWSemantic(), retrievalDefaults.getRerankWSemantic())));
        boolean rewrite = firstNonNull(command.getRewriteEnabled(),
                firstNonNull(kb.getRewriteEnabled(), retrievalDefaults.isRewriteEnabled()));

        return new RetrievalSettings(recallTopK, topN, threshold,
                FusionParams.of(mode, rrfK, wVec), rerank, rerankMode, rerankWSemantic, rewrite);
    }

    /**
     * Applies the configured default and upper bound to an optional tuning parameter.
     *
     * @param requested requested value, {@code null} or non positive for the default
     * @param fallback  configured default
     * @param maximum   configured upper bound
     * @return effective value
     */
    private static int clamp(Integer requested, int fallback, int maximum) {
        if (requested == null || requested < 1) {
            return fallback;
        }
        return Math.min(requested, maximum);
    }

    /**
     * Confines a semantic weight to {@code [0,1]} so a knowledge base column edited out of range cannot
     * push the hybrid blend outside the unit interval the request annotations already guarantee.
     *
     * @param weight resolved weight from any configuration layer
     * @return weight within {@code [0,1]}
     */
    private static double clampWeight(double weight) {
        return Math.max(0.0d, Math.min(1.0d, weight));
    }

    private static <T> T firstNonNull(T preferred, T fallback) {
        return preferred != null ? preferred : fallback;
    }
}
