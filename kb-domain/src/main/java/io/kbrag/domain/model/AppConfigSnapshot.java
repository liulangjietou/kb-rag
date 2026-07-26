package io.kbrag.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Typed view of {@code t_kb_app_version.config}: everything a call to the open API resolves against,
 * requirement section 4.7 "configuration snapshot".
 *
 * <p>Layer four of the five layer configuration model, and the only one that does <b>not</b> fall back to
 * the layers below it. A released snapshot is the configuration the release gate measured; letting an
 * unset field fall through to the current knowledge base default would mean the gate validated something
 * else than what production runs, so the snapshot is written complete at creation time and read verbatim.
 *
 * <p>{@code kbId} is a single value on purpose: M4c limits an application to one knowledge base, and
 * multi base routing with quota weights arrives with M5 (requirement section 4.7 "staged limit").
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AppConfigSnapshot {

    /** Knowledge base this application serves; multi base support arrives with M5. */
    @JsonProperty("kb_id")
    private String kbId;

    /** Frozen retrieval parameters, written complete rather than sparse. */
    @JsonProperty("retrieval")
    private KbRetrievalConfig retrieval = new KbRetrievalConfig();

    /** Frozen question answering prompt configuration. */
    @JsonProperty("prompt")
    private AppPromptConfig prompt = AppPromptConfig.defaults();

    /**
     * Generation model of the chat endpoint, blank keeps the deployment default chat model, requirement
     * section 4.7 "the generation model belongs to the application version snapshot".
     */
    @JsonProperty("chat_model")
    private String chatModel;

    /** Absolute gate thresholds, consulted only on a first release that has no baseline to compare with. */
    @JsonProperty("gate")
    private GateThresholds gate;

    /**
     * Retrieval block, never {@code null}.
     *
     * @return frozen retrieval parameters
     */
    public KbRetrievalConfig retrievalOrDefaults() {
        return retrieval == null ? new KbRetrievalConfig() : retrieval;
    }

    /**
     * Prompt block, never {@code null}.
     *
     * @return frozen prompt configuration
     */
    public AppPromptConfig promptOrDefaults() {
        return prompt == null ? AppPromptConfig.defaults() : prompt;
    }

    /**
     * Absolute gate thresholds of a first release.
     *
     * @param minHitRate minimum {@code Hit Rate} a first release must reach, {@code null} unset
     * @param minRecall  minimum {@code Recall@K} a first release must reach, {@code null} unset
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GateThresholds(
            @JsonProperty("min_hit_rate") Double minHitRate,
            @JsonProperty("min_recall") Double minRecall) {

        /**
         * Tells whether any absolute threshold is actually configured.
         *
         * @return {@code true} when at least one bound is set
         */
        public boolean configured() {
            return minHitRate != null || minRecall != null;
        }
    }
}
