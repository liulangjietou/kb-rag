package io.kbrag.domain.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The two metrics the release gate is allowed to decide on, requirement section 4.7 "core metrics
 * (Recall@K / Hit Rate) must not be below the baseline".
 *
 * <p>Deliberately narrow. Precision, MRR and NDCG are reported but never gated, the confidence intervals
 * are display only (the gate's noise control is the tolerance, and the requirement forbids stacking the
 * two mechanisms), and the LLM-as-judge answer score is excluded outright since a generation model's
 * opinion must not be able to block a retrieval release.
 *
 * @param hitRate share of effective cases with at least one relevant unit in the top {@code K}
 * @param recall  mean per case {@code Recall@K} over the effective cases
 *
 * @author owlzhangfq@gmail.com
 */
public record GateCoreMetrics(
        @JsonProperty("hit_rate") double hitRate,
        double recall) {
}
