package io.kbrag.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

/**
 * Typed view of {@code t_kb_app_version.config}: everything a call to the open API resolves against,
 * requirement section 4.7 "configuration snapshot".
 *
 * <p>Layer four of the five layer configuration model, and the only one that does <b>not</b> fall back to
 * the layers below it. A released snapshot is the configuration the release gate measured; letting an
 * unset field fall through to the current knowledge base default would mean the gate validated something
 * else than what production runs, so the snapshot is written complete at creation time and read verbatim.
 *
 * <p><b>One reader, two shapes on disk.</b> Snapshots frozen before M5 carry a single {@code kb_id};
 * from M5 on an application links one to fifteen bases through {@code kb_refs} with quota weights
 * (requirement section 4.7). Migrating the stored rows is not an option - a released snapshot is
 * evidence behind a gate verdict and must stay byte for byte what the gate measured - so the legacy
 * shape is translated on read, in {@link #getKbRefs()} and nowhere else. That single accessor is why no
 * caller of this class ever has to know which shape it was handed.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AppConfigSnapshot {

    /**
     * Legacy single knowledge base field of the pre M5 snapshots.
     *
     * <p>Accepted on read and never written back: {@link #getKbRefs()} is the only way to reach it, so a
     * caller cannot branch on the storage shape and no code path can start writing it again.
     */
    @Getter(AccessLevel.NONE)
    @JsonProperty(value = "kb_id", access = JsonProperty.Access.WRITE_ONLY)
    private String kbId;

    /** Knowledge bases this application serves with their quota weights, M5 shape. */
    @Getter(AccessLevel.NONE)
    private List<KbRef> kbRefs;

    /** Routing block, off unless an operator turned it on. */
    @JsonProperty("routing")
    private AppRoutingConfig routing;

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
     * Knowledge bases of this application, legacy shape translated, never {@code null}.
     *
     * <p>Translation only: an entry without an id cannot name a base and is dropped, but a weight is
     * returned exactly as it was written. Repairing a weight here would hide it from the validation that
     * rejects it (a zero weight is an operator mistake, not a default), and the tolerance the retrieval
     * side needs already lives in {@link KbRef#effectiveWeight()}.
     *
     * <p>The declaration order is meaningful and preserved: it is the tie breaker of the quota remainder
     * and the base whose knowledge base level defaults complete an unset retrieval parameter.
     *
     * @return references in declaration order, empty when the version has no base configured
     */
    @JsonProperty("kb_refs")
    public List<KbRef> getKbRefs() {
        if (kbRefs != null && !kbRefs.isEmpty()) {
            List<KbRef> present = new ArrayList<>(kbRefs.size());
            for (KbRef ref : kbRefs) {
                if (ref != null && ref.kbId() != null && !ref.kbId().isBlank()) {
                    present.add(ref);
                }
            }
            return present;
        }
        return kbId == null || kbId.isBlank() ? List.of() : List.of(KbRef.of(kbId));
    }

    /**
     * Business ids of the linked knowledge bases, in declaration order.
     *
     * @return knowledge base ids, empty when the version has no base configured
     */
    public List<String> kbIds() {
        return getKbRefs().stream().map(KbRef::kbId).toList();
    }

    /**
     * Routing block, never {@code null}.
     *
     * @return frozen routing configuration
     */
    public AppRoutingConfig routingOrDefaults() {
        return routing == null ? AppRoutingConfig.defaults() : routing;
    }

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
