package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.entity.EvalRun;
import io.kbrag.domain.model.EvalMetricsAtK;
import io.kbrag.domain.model.EvalRetrievalConfig;

import java.util.Map;

/**
 * Evaluation run detail payload.
 *
 * @param runId              business identifier
 * @param datasetId           data set this run measured
 * @param kbId                owning knowledge base
 * @param datasetRevision     data set revision snapshotted at run creation
 * @param corpusFingerprint   corpus state this run measured against
 * @param retrievalConfig     label, mode and retrieval parameters this run used
 * @param judgeModel          LLM-as-judge model, {@code null} when judging was not requested
 * @param judgePromptVersion  judge prompt version, {@code null} when judging was not requested
 * @param status              lifecycle state
 * @param metrics             grouped metrics with 95% CI; {@code null} until the run finishes
 * @param caseTotal           cases in the data set at run start
 * @param caseEffective       cases actually judged
 * @param caseStale           cases skipped for stale evidence
 * @param caseDegraded        cases still degraded after the automatic retry
 * @param failReason          set only when {@code status} is {@code FAILED}
 * @param startedAt           ISO execution start timestamp
 * @param finishedAt          ISO execution end timestamp
 *
 * @author owlzhangfq@gmail.com
 */
public record EvalRunResponse(
        @JsonProperty("run_id") String runId,
        @JsonProperty("dataset_id") String datasetId,
        @JsonProperty("kb_id") String kbId,
        @JsonProperty("dataset_revision") Integer datasetRevision,
        @JsonProperty("corpus_fingerprint") String corpusFingerprint,
        @JsonProperty("retrieval_config") EvalRetrievalConfig retrievalConfig,
        @JsonProperty("judge_model") String judgeModel,
        @JsonProperty("judge_prompt_version") String judgePromptVersion,
        String status,
        Map<String, Map<String, EvalMetricsAtK>> metrics,
        @JsonProperty("case_total") Integer caseTotal,
        @JsonProperty("case_effective") Integer caseEffective,
        @JsonProperty("case_stale") Integer caseStale,
        @JsonProperty("case_degraded") Integer caseDegraded,
        @JsonProperty("fail_reason") String failReason,
        @JsonProperty("started_at") String startedAt,
        @JsonProperty("finished_at") String finishedAt) {

    /**
     * Maps a stored run onto its response.
     *
     * @param run stored run
     * @return response
     */
    public static EvalRunResponse from(EvalRun run) {
        return new EvalRunResponse(
                run.getRunId(),
                run.getDatasetId(),
                run.getKbId(),
                run.getDatasetRevision(),
                run.getCorpusFingerprint(),
                JsonUtil.parse(run.getRetrievalConfig(), EvalRetrievalConfig.class),
                run.getJudgeModel(),
                run.getJudgePromptVersion(),
                run.getStatus() == null ? null : run.getStatus().name(),
                run.getMetrics() == null ? null
                        : JsonUtil.parse(run.getMetrics(), new TypeReference<Map<String, Map<String, EvalMetricsAtK>>>() {
                        }),
                run.getCaseTotal(),
                run.getCaseEffective(),
                run.getCaseStale(),
                run.getCaseDegraded(),
                run.getFailReason(),
                run.getStartedAt() == null ? null : run.getStartedAt().toString(),
                run.getFinishedAt() == null ? null : run.getFinishedAt().toString());
    }
}
