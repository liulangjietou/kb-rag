package io.kbrag.domain.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Final-answer metrics recomputed on the cases both sides successfully judged.
 *
 * @param candidate candidate answer metrics
 * @param baseline baseline answer metrics, {@code null} on a first release
 * @param effectiveCases successfully judged common cases
 * @param judgeFailedCases common requested cases with a failed judgment on either side
 * @param degradedCases common cases whose retrieval remained degraded
 * @param caseIds successfully judged case ids
 *
 * @author owlzhangfq@gmail.com
 */
public record FinalAnswerGateComparison(
        FinalAnswerMetrics candidate,
        FinalAnswerMetrics baseline,
        @JsonProperty("effective_cases") int effectiveCases,
        @JsonProperty("judge_failed_cases") int judgeFailedCases,
        @JsonProperty("degraded_cases") int degradedCases,
        @JsonProperty("case_ids") List<String> caseIds) {

    /**
     * Tells whether this comparison has a released baseline side.
     *
     * @return {@code true} for later releases
     */
    public boolean hasBaseline() {
        return baseline != null;
    }
}
