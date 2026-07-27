package io.kbrag.domain.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Retrieval quality metrics of one group of cases at one {@code K}, requirement section 4.6.
 *
 * <p>{@code K} always names the length of the final returned list - after fusion, rerank, parent
 * merge, threshold and {@code top_n} - never the recall stage's {@code recall_top_k}; see the
 * requirement document's "K definition" note. When parent child splitting is on, {@code K} counts
 * merged parent units.
 *
 * @param recall     {@code Recall@K}: for span cases, evidences covered over evidences total; for
 *                   document cases, relevant documents recalled over relevant documents total
 * @param precision  {@code Precision@K}: relevant returned units over {@code K}
 * @param hitRate    share of cases with at least one relevant unit in the top {@code K}
 * @param mrr        mean reciprocal rank of the first relevant unit, 0 when none was recalled
 * @param ndcg       {@code NDCG@K} with binary relevance
 * @param recallCi   95% Wilson interval of {@link #recall}, display only
 * @param hitRateCi  95% Wilson interval of {@link #hitRate}, display only
 *
 * @author owlzhangfq@gmail.com
 */
public record EvalMetricsAtK(
        double recall,
        double precision,
        @JsonProperty("hit_rate") double hitRate,
        double mrr,
        double ndcg,
        @JsonProperty("recall_ci") ConfidenceInterval recallCi,
        @JsonProperty("hit_rate_ci") ConfidenceInterval hitRateCi) {
}
