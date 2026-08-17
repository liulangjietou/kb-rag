package io.kbrag.domain.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Aggregated final-answer quality metrics of one evaluation run.
 *
 * @param score                mean overall answer score
 * @param correctness          mean correctness score
 * @param faithfulness         mean faithfulness score
 * @param completeness         mean completeness score
 * @param citationCorrectness  mean citation correctness score
 * @param citationCompleteness mean citation completeness score
 * @param refusalAccuracy      correct refuse/answer decisions divided by judged cases
 * @param evaluatedCases       cases with a valid final-answer judgment
 * @param judgeFailedCases     answer cases whose judge call did not yield a valid judgment
 * @param latencyP95Ms         generation latency 95th percentile in milliseconds
 *
 * @author owlzhangfq@gmail.com
 */
public record FinalAnswerMetrics(
        double score,
        double correctness,
        double faithfulness,
        double completeness,
        @JsonProperty("citation_correctness") double citationCorrectness,
        @JsonProperty("citation_completeness") double citationCompleteness,
        @JsonProperty("refusal_accuracy") double refusalAccuracy,
        @JsonProperty("evaluated_cases") int evaluatedCases,
        @JsonProperty("judge_failed_cases") int judgeFailedCases,
        @JsonProperty("latency_p95_ms") int latencyP95Ms) {
}
