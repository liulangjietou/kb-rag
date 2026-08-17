package io.kbrag.domain.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Candidate-minus-baseline deltas used by the final-answer release gate.
 *
 * @param score overall score delta
 * @param correctness correctness delta
 * @param faithfulness faithfulness delta
 * @param completeness completeness delta
 * @param citationCorrectness citation correctness delta
 * @param citationCompleteness citation completeness delta
 * @param refusalAccuracy refusal decision accuracy delta
 *
 * @author owlzhangfq@gmail.com
 */
public record FinalAnswerMetricDeltas(
        double score,
        double correctness,
        double faithfulness,
        double completeness,
        @JsonProperty("citation_correctness") double citationCorrectness,
        @JsonProperty("citation_completeness") double citationCompleteness,
        @JsonProperty("refusal_accuracy") double refusalAccuracy) {
}
