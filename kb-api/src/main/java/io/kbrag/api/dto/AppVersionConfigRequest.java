package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.domain.model.AppConfigSnapshot;
import io.kbrag.domain.model.AppPromptConfig;
import io.kbrag.domain.model.KbRetrievalConfig;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Version creation payload: the console's configuration form.
 *
 * <p>Every retrieval field is optional and a {@code null} inherits the newest version's value, so changing one
 * parameter of what is live does not require restating the whole configuration. Once the version leaves the
 * draft state the snapshot is completed and frozen, and nothing falls back to a live default afterwards.
 *
 * @param kbId          knowledge base the application serves; a single one until M5 unlocks multi base
 * @param retrieval     retrieval parameters
 * @param prompt        question answering prompt configuration
 * @param gate          absolute gate thresholds, only consulted on a first release
 * @param chatModel     generation model frozen into the snapshot, blank keeps the deployment default
 * @param gateDatasetId baseline evaluation data set of the release gate
 * @param changelog     version description
 *
 * @author owlzhangfq@gmail.com
 */
public record AppVersionConfigRequest(
        @JsonProperty("kb_id") String kbId,
        @Valid RetrievalRequest retrieval,
        @Valid PromptRequest prompt,
        @Valid GateThresholdRequest gate,
        @JsonProperty("chat_model") String chatModel,
        @JsonProperty("gate_dataset_id") String gateDatasetId,
        @Size(max = 1024, message = "must be at most 1024 characters") String changelog) {

    /**
     * Retrieval parameters of a version.
     *
     * @param recallTopK     candidates recalled per route
     * @param topN           number of returned nodes
     * @param fusionMode     fusion strategy literal
     * @param wVec           vector weight of the weighted strategy
     * @param rrfK           damping constant of the reciprocal rank strategy
     * @param scoreThreshold absolute score threshold, {@code null} disables filtering
     * @param rerankEnabled  rerank switch
     * @param rewriteEnabled query rewrite switch
     */
    public record RetrievalRequest(
            @JsonProperty("recall_top_k") @Min(value = 1, message = "must be at least 1") Integer recallTopK,
            @JsonProperty("top_n") @Min(value = 1, message = "must be at least 1") Integer topN,
            @JsonProperty("fusion_mode") String fusionMode,
            @JsonProperty("w_vec")
            @DecimalMin(value = "0.0", message = "must be at least 0")
            @DecimalMax(value = "1.0", message = "must be at most 1") Double wVec,
            @JsonProperty("rrf_k") @Min(value = 1, message = "must be at least 1") Integer rrfK,
            @JsonProperty("score_threshold")
            @DecimalMin(value = "0.01", message = "must be at least 0.01")
            @DecimalMax(value = "1.0", message = "must be at most 1.0") Double scoreThreshold,
            @JsonProperty("rerank_enabled") Boolean rerankEnabled,
            @JsonProperty("rewrite_enabled") Boolean rewriteEnabled) {
    }

    /**
     * Prompt configuration of a version.
     *
     * @param systemPrompt     application system instruction
     * @param refusalEnabled   refuse to answer without supporting material
     * @param refusalPrompt    operator wording of the refusal instruction
     * @param leakGuardEnabled forbid revealing the prompt and retrieval internals
     * @param leakGuardPrompt  operator wording of the leak guard instruction
     * @param citationEnabled  ask the model to cite the numbered passages
     */
    public record PromptRequest(
            @JsonProperty("system_prompt") @Size(max = 4000, message = "must be at most 4000 characters")
            String systemPrompt,
            @JsonProperty("refusal_enabled") Boolean refusalEnabled,
            @JsonProperty("refusal_prompt") @Size(max = 2000, message = "must be at most 2000 characters")
            String refusalPrompt,
            @JsonProperty("leak_guard_enabled") Boolean leakGuardEnabled,
            @JsonProperty("leak_guard_prompt") @Size(max = 2000, message = "must be at most 2000 characters")
            String leakGuardPrompt,
            @JsonProperty("citation_enabled") Boolean citationEnabled) {
    }

    /**
     * Absolute gate thresholds of a first release.
     *
     * @param minHitRate minimum {@code Hit Rate}
     * @param minRecall  minimum {@code Recall@K}
     */
    public record GateThresholdRequest(
            @JsonProperty("min_hit_rate")
            @DecimalMin(value = "0.0", message = "must be at least 0")
            @DecimalMax(value = "1.0", message = "must be at most 1") Double minHitRate,
            @JsonProperty("min_recall")
            @DecimalMin(value = "0.0", message = "must be at least 0")
            @DecimalMax(value = "1.0", message = "must be at most 1") Double minRecall) {
    }

    /**
     * Maps the transport shape onto the configuration snapshot, or {@code null} when the payload carried no
     * configuration at all and the newest version's should be inherited.
     *
     * @return configuration snapshot, {@code null} to inherit
     */
    public AppConfigSnapshot toSnapshot() {
        if (kbId == null && retrieval == null && prompt == null && gate == null && chatModel == null) {
            return null;
        }
        AppConfigSnapshot snapshot = new AppConfigSnapshot();
        snapshot.setKbId(kbId);
        snapshot.setRetrieval(toRetrieval());
        snapshot.setPrompt(toPrompt());
        snapshot.setChatModel(chatModel);
        snapshot.setGate(gate == null ? null
                : new AppConfigSnapshot.GateThresholds(gate.minHitRate(), gate.minRecall()));
        return snapshot;
    }

    private KbRetrievalConfig toRetrieval() {
        KbRetrievalConfig config = new KbRetrievalConfig();
        if (retrieval == null) {
            return config;
        }
        config.setRecallTopK(retrieval.recallTopK());
        config.setTopN(retrieval.topN());
        config.setFusionMode(retrieval.fusionMode());
        config.setWVec(retrieval.wVec());
        config.setRrfK(retrieval.rrfK());
        config.setScoreThreshold(retrieval.scoreThreshold());
        config.setRerankEnabled(retrieval.rerankEnabled());
        config.setRewriteEnabled(retrieval.rewriteEnabled());
        return config;
    }

    private AppPromptConfig toPrompt() {
        AppPromptConfig config = AppPromptConfig.defaults();
        if (prompt == null) {
            return config;
        }
        config.setSystemPrompt(prompt.systemPrompt());
        config.setRefusalPrompt(prompt.refusalPrompt());
        config.setLeakGuardPrompt(prompt.leakGuardPrompt());
        // The three switches default to on, so only an explicit false turns one off; a null keeps the default
        // rather than silently disabling a guard the operator never touched.
        config.setRefusalEnabled(!Boolean.FALSE.equals(prompt.refusalEnabled()));
        config.setLeakGuardEnabled(!Boolean.FALSE.equals(prompt.leakGuardEnabled()));
        config.setCitationEnabled(!Boolean.FALSE.equals(prompt.citationEnabled()));
        return config;
    }
}
