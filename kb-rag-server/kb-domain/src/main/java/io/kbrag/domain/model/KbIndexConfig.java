package io.kbrag.domain.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.List;

/**
 * Typed view of the {@code t_kb_knowledge_base.index_config} JSON column.
 *
 * <p>The column is the third layer of the configuration model: it overrides the deployment defaults
 * for one knowledge base and is the input of the split fingerprint, which is what lets a
 * configuration change be detected per document instead of forcing a blanket rebuild.
 *
 * <p>The two length fields carry a read alias for the names M1 wrote, so a knowledge base created
 * before the rename keeps its configured lengths instead of silently falling back to zero; every
 * write emits the current names only.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class KbIndexConfig {

    /** Splitter strategy code. */
    @JsonProperty("split_strategy")
    private String splitStrategy;

    /** Maximum estimated tokens per chunk, or per child when parent child splitting is on. */
    @JsonProperty("chunk_max_tokens")
    @JsonAlias("max_tokens")
    private int chunkMaxTokens;

    /** Overlap in estimated tokens. */
    @JsonProperty("chunk_overlap")
    @JsonAlias("overlap_tokens")
    private int chunkOverlap;

    /** Embedding model recorded at creation time, informational. */
    @JsonProperty("embedding_model")
    private String embeddingModel;

    /** Two level splitting parameters. */
    @JsonProperty("parent_child")
    private ParentChildParams parentChild = ParentChildParams.disabled();

    /** Cleaning rules applied between the image stage and the split stage. */
    @JsonProperty("clean_rules")
    private CleanRules cleanRules = CleanRules.defaults();

    /**
     * Suspends the pipeline after cleaning so a human confirms the parse result before it is indexed.
     *
     * <p>Off by default: a bulk upload of hundreds of files would otherwise stop on every one of them,
     * which is exactly the workflow the requirement wants to keep frictionless.
     */
    @JsonProperty("parse_preview_required")
    private boolean parsePreviewRequired;

    /** Windowing of a chat import. */
    @JsonProperty("chat_aggregation")
    private ChatAggregationParams chatAggregation = ChatAggregationParams.defaults();

    /**
     * Operator declared metadata extraction rules, the M14 contract section 3.1, at most
     * {@link MetadataRule#MAX_RULES}.
     *
     * <p>Inside the chunk fingerprint: the rules decide what metadata a build stamps on every chunk,
     * so editing them has to mark the affected documents configuration stale exactly like changing
     * the splitter would.
     */
    @JsonProperty("metadata_rules")
    private List<MetadataRule> metadataRules;

    /**
     * Block delimiter of the M14 {@code separator} splitting strategy, {@code null} lets the strategy
     * fall back to its own default; ignored by every other strategy.
     */
    @JsonProperty("split_separator")
    private String splitSeparator;

    /** Whether {@link #splitSeparator} is a regular expression, the M14 {@code separator} strategy. */
    @JsonProperty("split_separator_is_regex")
    private boolean splitSeparatorIsRegex;

    /**
     * Markdown heading depth of the M14 {@code heading} splitting strategy, {@code 0} lets the strategy
     * fall back to its own default depth; ignored by every other strategy.
     */
    @JsonProperty("split_heading_level")
    private int splitHeadingLevel;

    /**
     * Turns on the multimodal vector route for this knowledge base, the M14 contract section 6.2.
     *
     * <p>Off by default so shipping the feature changes nothing: a knowledge base that never set it
     * keeps the exact chunk fingerprint it had before M14. When on and a multimodal provider is
     * configured, the pipeline produces an extra vector for every image bearing chunk and retrieval
     * gains a third recall route. When on but no provider is configured the switch is stored and the
     * indexing is skipped, the same zero key tolerance the text vector route already has.
     *
     * <p>Inside the chunk fingerprint: flipping it changes what the pipeline produces, so it has to
     * mark the affected documents configuration stale exactly like changing the splitter would.
     */
    @JsonProperty("multimodal_enabled")
    private boolean multimodalEnabled;

    /**
     * Suppresses a parent chunk entirely as soon as one of its children is disabled.
     *
     * <p>Off by default. The one phase semantics return the whole parent and merely report which of
     * its children are disabled, which keeps the passage readable; a deployment that must never let a
     * disabled sentence reach a caller turns this on and accepts losing the surrounding context.
     *
     * <p>Deliberately outside both fingerprints: the switch changes what retrieval returns, not what
     * the pipeline produces, so flipping it must not mark a single document as configuration stale.
     */
    @JsonProperty("hide_parent_with_disabled_child")
    private boolean hideParentWithDisabledChild;

    /**
     * Carries disable annotations over to a new document version when the chunk text is byte identical.
     *
     * <p>On by default: a chunk whose normalised text did not change is the same knowledge, and losing
     * its disabled state on every re-upload would silently re-expose content somebody removed on
     * purpose. Matching is exact — no similarity heuristic — so an edited chunk is never assumed to be
     * the same one.
     *
     * <p>Outside both fingerprints for the same reason as the switch above.
     */
    @JsonProperty("inherit_disable_annotation")
    private boolean inheritDisableAnnotation = true;

    /**
     * Resolves the cleaning rules, never {@code null}.
     *
     * @return cleaning rules, defaults when the column carried none
     */
    public CleanRules cleanRulesOrDefaults() {
        return cleanRules == null ? CleanRules.defaults() : cleanRules;
    }

    /**
     * Resolves the chat aggregation parameters, never {@code null}.
     *
     * @return chat aggregation parameters, defaults when the column carried none
     */
    public ChatAggregationParams chatAggregationOrDefaults() {
        return chatAggregation == null ? ChatAggregationParams.defaults() : chatAggregation;
    }

    /**
     * Resolves the metadata extraction rules, never {@code null}.
     *
     * @return declared rules, empty when the column carried none
     */
    public List<MetadataRule> metadataRulesOrEmpty() {
        return metadataRules == null ? List.of() : metadataRules;
    }

    /**
     * Resolves the parent child parameters, never {@code null}.
     *
     * @return parent child parameters, disabled when the column carried none
     */
    public ParentChildParams parentChildOrDisabled() {
        return parentChild == null ? ParentChildParams.disabled() : parentChild;
    }

    /**
     * Tells whether documents of this knowledge base are split into two levels.
     *
     * @return {@code true} when parent child splitting is on
     */
    public boolean parentChildEnabled() {
        return parentChildOrDisabled().isEnabled();
    }

    /**
     * Split parameters of the single level pipeline.
     *
     * <p>Only meaningful while parent child splitting is off; the two level pipeline takes its lengths
     * from {@link ParentChildParams} instead, so there is deliberately no fallback here that would let a
     * caller split a two level knowledge base with the single level budget.
     *
     * @return validated split parameters
     */
    public SplitParams splitParams() {
        return splitParams(null);
    }

    /**
     * Split parameters of the single level pipeline, carrying the LLM semantic splitter's object
     * storage cache coordinates.
     *
     * @param cacheContext cache coordinates, {@code null} when the caller does not need caching
     * @return validated split parameters
     */
    public SplitParams splitParams(SplitParams.CacheContext cacheContext) {
        return SplitParams.of(chunkMaxTokens, chunkOverlap, cacheContext, splitSeparator,
                splitSeparatorIsRegex, splitHeadingLevel);
    }
}
