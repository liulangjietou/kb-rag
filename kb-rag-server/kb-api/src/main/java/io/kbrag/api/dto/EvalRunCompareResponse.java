package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.kbrag.app.eval.EvalRunService;

import java.util.List;

/**
 * Response of {@code GET /api/v1/eval-runs/compare}, requirement section 4.6 "corpus change must be
 * flagged".
 *
 * @param comparable {@code true} when every run shares both {@code dataset_revision} and
 *                   {@code corpus_fingerprint}
 * @param reason     explanation, always present, {@code null} when comparable
 * @param runs       the runs that were compared, in the order they were requested
 *
 * @author owlzhangfq@gmail.com
 */
public record EvalRunCompareResponse(
        boolean comparable,
        @JsonInclude(JsonInclude.Include.ALWAYS) String reason,
        List<EvalRunResponse> runs) {

    /**
     * Maps a service outcome onto its response.
     *
     * @param result compare outcome
     * @return response
     */
    public static EvalRunCompareResponse from(EvalRunService.CompareResult result) {
        return new EvalRunCompareResponse(result.comparable(), result.reason(),
                result.runs().stream().map(EvalRunResponse::from).toList());
    }
}
