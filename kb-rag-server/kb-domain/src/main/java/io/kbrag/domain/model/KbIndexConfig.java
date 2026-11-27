package io.kbrag.domain.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import io.kbrag.domain.service.FixedLengthTextSplitter;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
     * Similarity drop threshold of the embedding semantic strategy; {@code 0} keeps the strategy default.
     */
    @JsonProperty("split_semantic_drop_threshold")
    private double splitSemanticDropThreshold;

    /**
     * Per file extension split overrides, for example {@code {"xlsx":"table_row"}}.
     */
    @JsonProperty("split_strategy_by_ext")
    private Map<String, String> splitStrategyByExt;

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
                splitSeparatorIsRegex, splitHeadingLevel, splitSemanticDropThreshold,
                cleanRulesOrDefaults().isExcelHeaderJoin(), 0);
    }

    /**
     * Resolves the extension map, never {@code null}.
     *
     * @return configured overrides, empty when never set
     */
    public Map<String, String> splitStrategyByExtOrEmpty() {
        return splitStrategyByExt == null ? Map.of() : splitStrategyByExt;
    }

    /**
     * Chooses the split strategy for one document extension.
     *
     * @param fileExt lower case extension without a dot, may be {@code null}
     * @return persisted strategy code
     */
    public String resolveSplitStrategy(String fileExt) {
        if (fileExt != null && !fileExt.isBlank() && splitStrategyByExt != null) {
            String override = splitStrategyByExt.get(fileExt.toLowerCase(Locale.ROOT));
            if (override != null && !override.isBlank()) {
                return override.trim().toLowerCase(Locale.ROOT);
            }
        }
        if (splitStrategy == null || splitStrategy.isBlank()) {
            return FixedLengthTextSplitter.STRATEGY_CODE;
        }
        return splitStrategy.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Normalised copy of the extension strategy map for fingerprints.
     *
     * <p>按存储顺序输出，不排序：存量库的默认映射是按 HashMap 迭代顺序写入的，改为排序会让它们的指纹
     * 整体变化、全部文档被误标过期。配置写入口按请求体顺序保存，控制台原样回传读到的顺序，因此稳定。
     *
     * @return extension to strategy entries in stored order
     */
    public Map<String, String> normalizedSplitStrategyByExt() {
        Map<String, String> normalized = new LinkedHashMap<>();
        splitStrategyByExtOrEmpty().forEach((ext, strategy) -> {
            if (ext == null || ext.isBlank() || strategy == null || strategy.isBlank()) {
                return;
            }
            normalized.put(ext.toLowerCase(Locale.ROOT), strategy.trim().toLowerCase(Locale.ROOT));
        });
        return normalized;
    }

    /**
     * 本库文档实际可能被切分时用到的全部策略：库级默认策略，加上每条扩展名覆盖。
     *
     * <p>校验、模型可用性检查和指纹的参数折叠都必须以这个集合为准。只看库级 {@code split_strategy}
     * 时，一条 {@code txt -> separator} 覆盖读到的分隔符既不被校验也不进指纹；一条
     * {@code xlsx -> table_row} 覆盖在父子分片下也躲过了"仅允许 fixed_length"的拦截。
     *
     * @return 归一化后的策略码，库级默认策略排在首位
     */
    public Set<String> configuredSplitStrategies() {
        Set<String> strategies = new LinkedHashSet<>();
        strategies.add(resolveSplitStrategy(null));
        strategies.addAll(normalizedSplitStrategyByExt().values());
        return strategies;
    }

    /**
     * Tells whether any document of this knowledge base may be split with the given strategy.
     *
     * @param strategyCode persisted strategy code
     * @return {@code true} when the default strategy or an extension override names it
     */
    public boolean usesSplitStrategy(String strategyCode) {
        return configuredSplitStrategies().contains(strategyCode);
    }
}
