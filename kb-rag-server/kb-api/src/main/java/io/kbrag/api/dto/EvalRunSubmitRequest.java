package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.enums.EvalMode;
import io.kbrag.domain.model.EvalRetrievalConfig;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Payload of {@code POST /api/v1/eval-datasets/{datasetId}/runs} and of the sibling
 * {@code .../runs/estimate} endpoint, requirement section 4.6 configuration matrix.
 *
 * @param k       metrics {@code K}, the default {@code top_n} of every configuration that does not
 *                override it
 * @param configs 1 to 6 configurations to compare, one run produced per entry
 * @param judge   optional LLM-as-judge switch
 * @param answer  optional final-answer evaluation configuration
 *
 * @author owlzhangfq@gmail.com
 */
public record EvalRunSubmitRequest(
        @Min(value = 1, message = "must be at least 1") int k,
        @NotEmpty(message = "must contain between 1 and 6 entries") @Valid List<ConfigRequest> configs,
        @Valid JudgeRequest judge,
        @Valid AnswerRequest answer) {

    /**
     * Maps every configuration onto the domain model, the single fast-fail gate of this payload.
     *
     * @return domain configurations, in submission order
     */
    public List<EvalRetrievalConfig> toConfigs() {
        return configs.stream().map(ConfigRequest::toConfig).toList();
    }

    /**
     * Tells whether the judge stage was requested.
     *
     * @return {@code true} when {@code judge.enabled} was set
     */
    public boolean judgeEnabled() {
        return judge != null && judge.enabled();
    }

    /**
     * Tells whether the production final-answer path should be evaluated.
     *
     * @return {@code true} when {@code answer.enabled} was set
     */
    public boolean answerEnabled() {
        return answer != null && answer.enabled();
    }

    /**
     * Application version selected for final-answer generation.
     *
     * @return version id, {@code null} when answer evaluation was not requested
     */
    public String answerAppVersionId() {
        return answer == null ? null : answer.appVersionId();
    }

    /**
     * One row of the configuration matrix.
     *
     * @param label          display label of this configuration column
     * @param mode           {@code BM25_ONLY}/{@code VECTOR_ONLY}/{@code HYBRID}/{@code HYBRID_RERANK}
     * @param recallTopK     candidates recalled per route, {@code null} keeps the deployment default
     * @param topN           returned units, also this configuration's metrics {@code K}; {@code null}
     *                       falls back to the request level {@code k}
     * @param fusion         fusion strategy literal, {@code null} keeps the deployment default
     * @param scoreThreshold absolute score threshold, {@code null} disables filtering
     * @param rewriteEnabled query rewrite switch, {@code null} keeps the deployment default
     */
    public record ConfigRequest(
            @NotBlank(message = "must not be blank") String label,
            @NotBlank(message = "must not be blank") String mode,
            @JsonProperty("recall_top_k") Integer recallTopK,
            @JsonProperty("top_n") Integer topN,
            String fusion,
            @JsonProperty("score_threshold")
            @DecimalMin(value = "0.01", message = "must be at least 0.01")
            @DecimalMax(value = "1.0", message = "must be at most 1.0") Double scoreThreshold,
            @JsonProperty("rewrite_enabled") Boolean rewriteEnabled) {

        private EvalRetrievalConfig toConfig() {
            EvalMode parsedMode;
            try {
                parsedMode = EvalMode.from(mode);
            } catch (IllegalArgumentException e) {
                throw BizException.invalidParam(
                        "mode must be one of BM25_ONLY, VECTOR_ONLY, HYBRID, HYBRID_RERANK");
            }
            EvalRetrievalConfig config = new EvalRetrievalConfig();
            config.setLabel(label);
            config.setMode(parsedMode);
            config.setRecallTopK(recallTopK);
            config.setTopN(topN);
            config.setFusion(fusion);
            config.setScoreThreshold(scoreThreshold);
            config.setRewriteEnabled(rewriteEnabled);
            return config;
        }
    }

    /**
     * LLM-as-judge switch of a run submission.
     *
     * @param enabled {@code true} runs the judge stage alongside every case
     * @param model   accepted for forward compatibility; the judge model is a deployment level
     *                configuration ({@code EVAL_JUDGE_MODEL}) and is not overridden per submission
     */
    public record JudgeRequest(boolean enabled, String model) {
    }

    /**
     * Final-answer evaluation switch.
     *
     * @param enabled whether to generate and judge final answers
     * @param appVersionId application version whose prompt and generation model are frozen into each run
     */
    public record AnswerRequest(boolean enabled,
                                @JsonProperty("app_version_id") String appVersionId) {
    }
}
