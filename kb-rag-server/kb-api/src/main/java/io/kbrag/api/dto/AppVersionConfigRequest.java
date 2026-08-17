package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.domain.model.AppConfigSnapshot;
import io.kbrag.domain.model.AppPromptConfig;
import io.kbrag.domain.model.AppRoutingConfig;
import io.kbrag.domain.model.AnswerGateConfig;
import io.kbrag.domain.model.KbRef;
import io.kbrag.domain.model.KbRetrievalConfig;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Version creation payload: the console's configuration form.
 *
 * <p>Every retrieval field is optional and a {@code null} inherits the newest version's value, so changing one
 * parameter of what is live does not require restating the whole configuration. Once the version leaves the
 * draft state the snapshot is completed and frozen, and nothing falls back to a live default afterwards.
 *
 * <p><b>{@code kb_id} is accepted, not encouraged.</b> A client written against M4c sent a single base and must
 * keep working; it is folded into a one entry {@code kb_refs} list. When both are present {@code kb_refs} wins,
 * because it is the shape that can express what the other cannot.
 *
 * @param kbId          legacy single knowledge base, folded into {@code kbRefs} when that one is absent
 * @param kbRefs        knowledge bases the application serves with their quota weights, one to fifteen
 * @param routing       knowledge base routing switch and prompt
 * @param retrieval     retrieval parameters
 * @param prompt        question answering prompt configuration
 * @param gate          absolute gate thresholds, only consulted on a first release
 * @param answerGate final-answer gate opt-in and first-release thresholds
 * @param chatModel     generation model frozen into the snapshot, blank keeps the deployment default
 * @param gateDatasetId baseline evaluation data set of the release gate
 * @param changelog     version description
 *
 * @author owlzhangfq@gmail.com
 */
public record AppVersionConfigRequest(
        @JsonProperty("kb_id") String kbId,
        @JsonProperty("kb_refs") @Valid List<KbRefRequest> kbRefs,
        @Valid RoutingRequest routing,
        @Valid RetrievalRequest retrieval,
        @Valid PromptRequest prompt,
        @Valid GateThresholdRequest gate,
        @JsonProperty("answer_gate") @Valid AnswerGateRequest answerGate,
        @JsonProperty("chat_model") String chatModel,
        @JsonProperty("gate_dataset_id") String gateDatasetId,
        @Size(max = 1024, message = "must be at most 1024 characters") String changelog) {

    /**
     * One knowledge base link of a version.
     *
     * <p>The count, the duplicate check and the existence of the base are validated in the application layer
     * rather than here: bean validation can only see one payload, and those three rules are the authoritative
     * ones an API client must not be able to route around.
     *
     * @param kbId   knowledge base business id
     * @param weight quota weight, {@code null} reads as the default
     */
    public record KbRefRequest(
            @JsonProperty("kb_id") @NotBlank(message = "must not be blank") String kbId,
            @Min(value = 1, message = "must be at least 1") Integer weight) {
    }

    /**
     * Routing configuration of a version.
     *
     * @param enabled {@code true} lets a model choose which linked bases a query is searched in
     * @param prompt  operator wording of the routing instruction, blank keeps the built in one
     */
    public record RoutingRequest(
            Boolean enabled,
            @Size(max = 4000, message = "must be at most 4000 characters") String prompt) {
    }

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
     * Opt-in final-answer gate configuration.
     *
     * @param enabled explicitly enables generated-answer evaluation during release
     * @param minScore minimum first-release overall score
     * @param minFaithfulness minimum first-release faithfulness score
     * @param minCitationCorrectness minimum first-release citation correctness
     * @param minRefusalAccuracy minimum first-release answer/refuse decision accuracy
     */
    public record AnswerGateRequest(
            boolean enabled,
            @JsonProperty("min_score")
            @DecimalMin(value = "1.0", message = "must be at least 1")
            @DecimalMax(value = "5.0", message = "must be at most 5") Double minScore,
            @JsonProperty("min_faithfulness")
            @DecimalMin(value = "1.0", message = "must be at least 1")
            @DecimalMax(value = "5.0", message = "must be at most 5") Double minFaithfulness,
            @JsonProperty("min_citation_correctness")
            @DecimalMin(value = "1.0", message = "must be at least 1")
            @DecimalMax(value = "5.0", message = "must be at most 5") Double minCitationCorrectness,
            @JsonProperty("min_refusal_accuracy")
            @DecimalMin(value = "0.0", message = "must be at least 0")
            @DecimalMax(value = "1.0", message = "must be at most 1") Double minRefusalAccuracy) {
    }

    /**
     * Maps the transport shape onto the configuration snapshot, or {@code null} when the payload carried no
     * configuration at all and the newest version's should be inherited.
     *
     * @return configuration snapshot, {@code null} to inherit
     */
    public AppConfigSnapshot toSnapshot() {
        if (kbId == null && kbRefs == null && routing == null && retrieval == null && prompt == null
                && gate == null && answerGate == null && chatModel == null) {
            return null;
        }
        AppConfigSnapshot snapshot = new AppConfigSnapshot();
        snapshot.setKbRefs(toKbRefs());
        snapshot.setRouting(toRouting());
        snapshot.setRetrieval(toRetrieval());
        snapshot.setPrompt(toPrompt());
        snapshot.setChatModel(chatModel);
        snapshot.setGate(gate == null ? null
                : new AppConfigSnapshot.GateThresholds(gate.minHitRate(), gate.minRecall()));
        snapshot.setAnswerGate(toAnswerGate());
        return snapshot;
    }

    private AnswerGateConfig toAnswerGate() {
        if (answerGate == null) {
            return null;
        }
        AnswerGateConfig config = new AnswerGateConfig();
        config.setEnabled(answerGate.enabled());
        config.setMinScore(answerGate.minScore());
        config.setMinFaithfulness(answerGate.minFaithfulness());
        config.setMinCitationCorrectness(answerGate.minCitationCorrectness());
        config.setMinRefusalAccuracy(answerGate.minRefusalAccuracy());
        return config;
    }

    /**
     * Maps the transport shape of the knowledge base links, folding a legacy single base in.
     *
     * <p>The weight is carried through as written, {@code null} included: repairing it here would hide it from
     * the one validation that rejects it.
     *
     * @return references in declaration order, empty when the payload named no base
     */
    private List<KbRef> toKbRefs() {
        if (kbRefs != null) {
            return kbRefs.stream().map(ref -> new KbRef(ref.kbId(),
                    ref.weight() == null ? KbRef.DEFAULT_WEIGHT : ref.weight())).toList();
        }
        return kbId == null || kbId.isBlank() ? List.of() : List.of(KbRef.of(kbId));
    }

    private AppRoutingConfig toRouting() {
        AppRoutingConfig config = AppRoutingConfig.defaults();
        if (routing == null) {
            return config;
        }
        config.setEnabled(Boolean.TRUE.equals(routing.enabled()));
        config.setPrompt(routing.prompt());
        return config;
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
