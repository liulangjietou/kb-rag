package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.entity.EvalResult;

import java.util.List;

/**
 * Per case drill down row of one evaluation run.
 *
 * @param resultId         business identifier
 * @param runId            owning run
 * @param caseId           case judged
 * @param hit              {@code true} when the case was recalled in the top K; the database column is
 *                         a tinyint, this field is always a boolean, matching {@code chunk.enabled}
 * @param hitRank          one based rank of the first hit, always present, {@code null} when not hit
 * @param overlapRatios    best individual overlap ratio per evidence
 * @param recalledChunkIds chunk ids the top K returned for this case
 * @param degraded         degradation markers observed while judging
 * @param retryCount       automatic retries this case went through
 * @param judgeScore       LLM-as-judge score, {@code null} when not judged
 * @param judgeReason      LLM-as-judge free text rationale
 *
 * @author owlzhangfq@gmail.com
 */
public record EvalResultResponse(
        @JsonProperty("result_id") String resultId,
        @JsonProperty("run_id") String runId,
        @JsonProperty("case_id") String caseId,
        boolean hit,
        @JsonInclude(JsonInclude.Include.ALWAYS) @JsonProperty("hit_rank") Integer hitRank,
        @JsonProperty("overlap_ratios") List<Double> overlapRatios,
        @JsonProperty("recalled_chunk_ids") List<String> recalledChunkIds,
        List<String> degraded,
        @JsonProperty("retry_count") Integer retryCount,
        @JsonProperty("judge_score") Integer judgeScore,
        @JsonProperty("judge_reason") String judgeReason) {

    /**
     * Maps a stored result onto its response.
     *
     * @param result stored result
     * @return response
     */
    public static EvalResultResponse from(EvalResult result) {
        return new EvalResultResponse(
                result.getResultId(),
                result.getRunId(),
                result.getCaseId(),
                result.getHit() != null && result.getHit() == 1,
                result.getHitRank(),
                JsonUtil.parse(result.getOverlapRatios(), new TypeReference<List<Double>>() {
                }),
                JsonUtil.parse(result.getRecalledChunkIds(), new TypeReference<List<String>>() {
                }),
                JsonUtil.parse(result.getDegraded(), new TypeReference<List<String>>() {
                }),
                result.getRetryCount(),
                result.getJudgeScore(),
                result.getJudgeReason());
    }
}
