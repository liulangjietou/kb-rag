package io.kbrag.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Persisted form of a gate run, stored as {@code t_kb_app_version.gate_report} and rendered by the
 * console's dual run comparison panel, requirement section 4.7.
 *
 * <p>It carries the case accounting as well as the metrics because the requirement obliges the report to
 * show total, effective and pending review counts with their ratio: a comparison whose denominator is not
 * visible cannot be argued with.
 *
 * @param verdict         three state outcome literal
 * @param reason          classified reason literal
 * @param message         operator facing explanation of {@code reason}
 * @param candidate       candidate metrics on the intersection
 * @param baseline        baseline metrics on the intersection, {@code null} on a first release
 * @param effectiveCases  size of the intersection both sides were scored on
 * @param totalCases      cases the data set held when the runs started
 * @param staleCases      cases skipped for stale evidence
 * @param staleRatio      {@code staleCases / totalCases}
 * @param degradedCases   cases either side reported as degraded after retries
 * @param epsilon         tolerance applied to the comparison
 * @param hitRateDelta    candidate minus baseline {@code Hit Rate}, {@code null} on a first release
 * @param recallDelta     candidate minus baseline {@code Recall@K}, {@code null} on a first release
 * @param candidateRunId  evaluation run of the candidate configuration
 * @param baselineRunId   evaluation run of the released configuration, {@code null} on a first release
 * @param evaluatedAt     ISO timestamp the gate finished at
 * @param answerComparison final-answer common-case metrics, {@code null} when the answer gate is disabled
 * @param answerDecision final-answer verdict details, {@code null} when disabled
 *
 * @author owlzhangfq@gmail.com
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GateReport(
        String verdict,
        String reason,
        String message,
        GateCoreMetrics candidate,
        GateCoreMetrics baseline,
        @JsonProperty("effective_cases") int effectiveCases,
        @JsonProperty("total_cases") int totalCases,
        @JsonProperty("stale_cases") int staleCases,
        @JsonProperty("stale_ratio") double staleRatio,
        @JsonProperty("degraded_cases") int degradedCases,
        double epsilon,
        @JsonProperty("hit_rate_delta") Double hitRateDelta,
        @JsonProperty("recall_delta") Double recallDelta,
        @JsonProperty("candidate_run_id") String candidateRunId,
        @JsonProperty("baseline_run_id") String baselineRunId,
        @JsonProperty("evaluated_at") String evaluatedAt,
        @JsonProperty("case_ids") List<String> caseIds,
        @JsonProperty("answer_comparison") FinalAnswerGateComparison answerComparison,
        @JsonProperty("answer_decision") FinalAnswerGateDecision answerDecision) {
}
