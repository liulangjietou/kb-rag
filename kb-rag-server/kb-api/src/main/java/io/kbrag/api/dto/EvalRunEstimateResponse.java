package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.app.eval.EvalRunService;

/**
 * Response of {@code POST /api/v1/eval-datasets/{datasetId}/runs/estimate}, requirement section 4.6
 * "cost guard rail".
 *
 * @param embeddingCalls predicted embedding calls
 * @param rerankCalls    predicted rerank calls
 * @param rewriteCalls   predicted query rewrite calls
 * @param judgeCalls     predicted LLM-as-judge calls
 * @param generationCalls predicted final-answer generation calls
 * @param answerJudgeCalls predicted final-answer judge calls
 *
 * @author owlzhangfq@gmail.com
 */
public record EvalRunEstimateResponse(
        @JsonProperty("embedding_calls") long embeddingCalls,
        @JsonProperty("rerank_calls") long rerankCalls,
        @JsonProperty("rewrite_calls") long rewriteCalls,
        @JsonProperty("judge_calls") long judgeCalls,
        @JsonProperty("generation_calls") long generationCalls,
        @JsonProperty("answer_judge_calls") long answerJudgeCalls) {

    /**
     * Maps a service outcome onto its response.
     *
     * @param result estimate outcome
     * @return response
     */
    public static EvalRunEstimateResponse from(EvalRunService.EstimateResult result) {
        return new EvalRunEstimateResponse(result.embeddingCalls(), result.rerankCalls(),
                result.rewriteCalls(), result.judgeCalls(), result.generationCalls(),
                result.answerJudgeCalls());
    }
}
