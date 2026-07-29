package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.constant.ChunkMetadataKeys;
import io.kbrag.domain.model.ChatAggregationParams;
import io.kbrag.domain.model.CleanRules;
import io.kbrag.domain.model.KbIndexConfig;
import io.kbrag.domain.model.KbRetrievalConfig;
import io.kbrag.domain.model.MetadataRule;
import io.kbrag.domain.model.ParentChildParams;
import io.kbrag.domain.service.FixedLengthTextSplitter;
import io.kbrag.domain.service.HeadingTextSplitter;
import io.kbrag.domain.service.LlmSemanticTextSplitter;
import io.kbrag.domain.service.SeparatorTextSplitter;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * New index configuration of a knowledge base.
 *
 * <p>The payload mirrors the stored {@code index_config} document one for one, so the console can send
 * back what it read without a translation table. The retrieval defaults ride along optionally because
 * an operator usually tunes both in the same sitting, but only the split part feeds the fingerprint:
 * changing a retrieval default cannot invalidate a single stored chunk and must not mark anything for
 * rebuild.
 *
 * <p>The M3 blocks — cleaning rules, the parse preview switch and the chat aggregation window — travel
 * through this same endpoint rather than through endpoints of their own: all three live inside
 * {@code index_config}, all three change what a build produces, and one write is what keeps the recomputed
 * fingerprint consistent with the whole stored document.
 *
 * @param splitStrategy        splitter strategy code, {@code null} keeps the stored one; validated
 *                             against model availability by the service, not here (single fast-fail
 *                             gate, requirement section 4.3)
 * @param chunkMaxTokens       maximum estimated tokens per chunk
 * @param chunkOverlap         overlap in estimated tokens
 * @param parentChild          two level splitting parameters
 * @param cleanRules           cleaning rules, {@code null} keeps the stored ones
 * @param parsePreviewRequired parse preview switch, {@code null} keeps the stored value
 * @param chatAggregation      chat import window, {@code null} keeps the stored one
 * @param hideParentWithDisabledChild parent suppression switch, {@code null} keeps the stored value
 * @param inheritDisableAnnotation    disable inheritance switch, {@code null} keeps the stored value
 * @param metadataRules        operator declared metadata extraction rules, {@code null} keeps the
 *                             stored ones, an empty list clears them
 * @param splitSeparator       block delimiter of the {@code separator} strategy, {@code null} keeps the
 *                             stored one
 * @param splitSeparatorIsRegex whether the separator is a regular expression, {@code null} keeps the
 *                             stored value
 * @param splitHeadingLevel    markdown heading depth of the {@code heading} strategy, {@code null}
 *                             keeps the stored value
 * @param multimodalEnabled    multimodal page index switch, {@code null} keeps the stored value; the
 *                             switch is stored whatever the provider state is, the pipeline decides
 *                             at build time whether the vectors can be produced (M14 contract 6.2)
 * @param retrievalConfig      knowledge base level retrieval defaults, optional
 *
 * @author owlzhangfq@gmail.com
 */
public record UpdateIndexConfigRequest(
        @JsonProperty("split_strategy") String splitStrategy,
        @JsonProperty("chunk_max_tokens") @NotNull @Min(1) Integer chunkMaxTokens,
        @JsonProperty("chunk_overlap") @NotNull @Min(0) Integer chunkOverlap,
        @JsonProperty("parent_child") ParentChildRequest parentChild,
        @JsonProperty("clean_rules") CleanRules cleanRules,
        @JsonProperty("parse_preview_required") Boolean parsePreviewRequired,
        @JsonProperty("chat_aggregation") ChatAggregationParams chatAggregation,
        @JsonProperty("hide_parent_with_disabled_child") Boolean hideParentWithDisabledChild,
        @JsonProperty("inherit_disable_annotation") Boolean inheritDisableAnnotation,
        @JsonProperty("metadata_rules") List<MetadataRule> metadataRules,
        @JsonProperty("split_separator") String splitSeparator,
        @JsonProperty("split_separator_is_regex") Boolean splitSeparatorIsRegex,
        @JsonProperty("split_heading_level") Integer splitHeadingLevel,
        @JsonProperty("multimodal_enabled") Boolean multimodalEnabled,
        @JsonProperty("retrieval_config") KbRetrievalConfig retrievalConfig) {

    /** Longest separator an operator may configure, the M14 contract section 4. */
    private static final int MAX_SEPARATOR_LENGTH = 64;

    /**
     * Two level splitting parameters.
     *
     * @param enabled         two level switch
     * @param parentMaxTokens maximum estimated tokens of a parent chunk
     * @param childMaxTokens  maximum estimated tokens of a child chunk
     * @param childOverlap    overlap in estimated tokens between two children
     */
    public record ParentChildRequest(
            boolean enabled,
            @JsonProperty("parent_max_tokens") @Min(1) Integer parentMaxTokens,
            @JsonProperty("child_max_tokens") @Min(1) Integer childMaxTokens,
            @JsonProperty("child_overlap") @Min(0) Integer childOverlap) {
    }

    /**
     * Maps the payload onto the stored configuration, keeping the fields the caller cannot change.
     *
     * <p>This is the fast-fail gate of the configuration path: combinations the bean validation
     * annotations cannot express, such as an overlap that would make the splitter unable to advance,
     * are rejected here so no splitter has to defend itself against them at runtime.
     *
     * @param current configuration currently stored, supplies the strategy and the embedding model
     * @return new configuration
     */
    public KbIndexConfig toIndexConfig(KbIndexConfig current) {
        KbIndexConfig config = new KbIndexConfig();
        String resolvedStrategy = splitStrategy != null && !splitStrategy.isBlank()
                ? splitStrategy.trim()
                : (current.getSplitStrategy() == null ? FixedLengthTextSplitter.STRATEGY_CODE
                        : current.getSplitStrategy());
        config.setSplitStrategy(resolvedStrategy);
        config.setEmbeddingModel(current.getEmbeddingModel());
        config.setChunkMaxTokens(chunkMaxTokens);
        config.setChunkOverlap(chunkOverlap);
        config.setParentChild(toParentChild(current.parentChildOrDisabled()));
        config.setCleanRules(cleanRules == null ? current.cleanRulesOrDefaults() : cleanRules);
        config.setParsePreviewRequired(parsePreviewRequired == null
                ? current.isParsePreviewRequired() : parsePreviewRequired);
        config.setChatAggregation(chatAggregation == null
                ? current.chatAggregationOrDefaults() : chatAggregation);
        // Neither switch feeds a fingerprint: one changes what retrieval returns and the other what a new
        // version inherits, and neither changes a single stored chunk, so flipping them must not mark any
        // document as configuration stale.
        config.setHideParentWithDisabledChild(hideParentWithDisabledChild == null
                ? current.isHideParentWithDisabledChild() : hideParentWithDisabledChild);
        config.setInheritDisableAnnotation(inheritDisableAnnotation == null
                ? current.isInheritDisableAnnotation() : inheritDisableAnnotation);
        config.setMetadataRules(metadataRules == null ? current.metadataRulesOrEmpty() : metadataRules);
        config.setSplitSeparator(splitSeparator == null ? current.getSplitSeparator() : splitSeparator);
        config.setSplitSeparatorIsRegex(splitSeparatorIsRegex == null
                ? current.isSplitSeparatorIsRegex() : splitSeparatorIsRegex);
        config.setSplitHeadingLevel(splitHeadingLevel == null
                ? current.getSplitHeadingLevel() : splitHeadingLevel);
        config.setMultimodalEnabled(multimodalEnabled == null
                ? current.isMultimodalEnabled() : multimodalEnabled);
        validate(config);
        return config;
    }

    private ParentChildParams toParentChild(ParentChildParams current) {
        ParentChildParams params = new ParentChildParams();
        if (parentChild == null) {
            params.setEnabled(false);
            params.setParentMaxTokens(current.getParentMaxTokens());
            params.setChildMaxTokens(current.getChildMaxTokens());
            params.setChildOverlap(current.getChildOverlap());
            return params;
        }
        params.setEnabled(parentChild.enabled());
        params.setParentMaxTokens(orDefault(parentChild.parentMaxTokens(), current.getParentMaxTokens()));
        params.setChildMaxTokens(orDefault(parentChild.childMaxTokens(), current.getChildMaxTokens()));
        params.setChildOverlap(orDefault(parentChild.childOverlap(), current.getChildOverlap()));
        return params;
    }

    private void validate(KbIndexConfig config) {
        if (config.getChunkOverlap() >= config.getChunkMaxTokens()) {
            throw BizException.invalidParam("chunk_overlap must be smaller than chunk_max_tokens");
        }
        // Checked for every knowledge base rather than only for a two level one: cleaning and chat
        // aggregation apply whatever the splitter does, so running them inside the parent child branch
        // left an uncompilable pattern and an impossible window reachable through a single level base.
        validateCleaning(config);
        validateMetadataRules(config);
        validateSplitStrategy(config);
        ParentChildParams params = config.parentChildOrDisabled();
        if (!params.isEnabled()) {
            return;
        }
        if (params.getChildMaxTokens() > params.getParentMaxTokens()) {
            throw BizException.invalidParam("child_max_tokens must not exceed parent_max_tokens");
        }
        if (params.getChildOverlap() >= params.getChildMaxTokens()) {
            throw BizException.invalidParam("child_overlap must be smaller than child_max_tokens");
        }
    }

    /**
     * Rejects the cleaning and aggregation combinations the annotations cannot express.
     *
     * <p>Every operator supplied expression is compiled here rather than at indexing time: a pattern that
     * cannot compile would otherwise be discovered by a background worker on a document nobody is watching,
     * and the rule would silently do nothing.
     *
     * @param config configuration being written
     */
    private void validateCleaning(KbIndexConfig config) {
        CleanRules rules = config.cleanRulesOrDefaults();
        for (String pattern : rules.watermarkPatterns()) {
            requireCompilable(pattern, "strip_watermark_patterns");
        }
        for (CleanRules.RegexReplacement replacement : rules.replacements()) {
            requireCompilable(replacement == null ? null : replacement.getPattern(), "regex_replacements");
        }
        ChatAggregationParams aggregation = config.chatAggregationOrDefaults();
        if (aggregation.getWindowMinutes() <= 0 || aggregation.getMaxMessages() <= 0) {
            throw BizException.invalidParam("chat_aggregation window_minutes and max_messages must be positive");
        }
        // The only gate on the overlap. The aggregator clamps rather than checks, so an operator finds out
        // here that the value is impossible instead of silently getting a different window shape than the
        // one they configured.
        if (!aggregation.windowOverlapWithinBound()) {
            throw BizException.invalidParam("chat_aggregation window_overlap must be between 0 and "
                    + aggregation.maxWindowOverlap() + " for max_messages "
                    + aggregation.effectiveMaxMessages());
        }
    }

    /**
     * Rejects the split strategy combinations the annotations cannot express, the M14 contract section 4:
     * the only fast-fail gate for the new strategies, so every splitter can trust the parameters it reads.
     *
     * <p>A two level base is confined to {@code fixed_length} and {@code llm_semantic}: the three new
     * strategies cut on a boundary the parent child pass cannot honour without losing that boundary, so
     * the combination is refused rather than silently degraded. The separator and heading parameters are
     * range checked here so a splitter never meets a length or a depth it would have to clamp.
     *
     * @param config configuration being written
     */
    private void validateSplitStrategy(KbIndexConfig config) {
        String strategy = config.getSplitStrategy();
        if (config.parentChildOrDisabled().isEnabled()
                && !FixedLengthTextSplitter.STRATEGY_CODE.equals(strategy)
                && !LlmSemanticTextSplitter.STRATEGY_CODE.equals(strategy)) {
            throw BizException.invalidParam("parent_child splitting supports only fixed_length and "
                    + "llm_semantic strategies, not " + strategy);
        }
        if (SeparatorTextSplitter.STRATEGY_CODE.equals(strategy)) {
            validateSeparator(config);
        } else if (HeadingTextSplitter.STRATEGY_CODE.equals(strategy)) {
            validateHeadingLevel(config);
        }
    }

    private void validateSeparator(KbIndexConfig config) {
        String separator = config.getSplitSeparator();
        if (separator == null || separator.isEmpty()) {
            return;
        }
        if (separator.length() > MAX_SEPARATOR_LENGTH) {
            throw BizException.invalidParam("split_separator must be 1 to " + MAX_SEPARATOR_LENGTH
                    + " characters");
        }
        if (config.isSplitSeparatorIsRegex()) {
            requireCompilable(separator, "split_separator");
        }
    }

    private void validateHeadingLevel(KbIndexConfig config) {
        int level = config.getSplitHeadingLevel();
        if (level == 0) {
            return;
        }
        if (level < HeadingTextSplitter.MIN_HEADING_LEVEL || level > HeadingTextSplitter.MAX_HEADING_LEVEL) {
            throw BizException.invalidParam("split_heading_level must be "
                    + HeadingTextSplitter.MIN_HEADING_LEVEL + " to " + HeadingTextSplitter.MAX_HEADING_LEVEL);
        }
    }

    private void requireCompilable(String pattern, String field) {
        if (pattern == null || pattern.isBlank()) {
            return;
        }
        try {
            Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw BizException.invalidParam("invalid regular expression in " + field + ": " + pattern);
        }
    }

    /**
     * Rejects a malformed metadata rule set, the M14 contract section 3.1: the only fast-fail gate for
     * these rules, so the pipeline can extract with them without re-checking anything.
     *
     * @param config configuration being written
     */
    private void validateMetadataRules(KbIndexConfig config) {
        List<MetadataRule> rules = config.metadataRulesOrEmpty();
        if (rules.isEmpty()) {
            return;
        }
        if (rules.size() > MetadataRule.MAX_RULES) {
            throw BizException.invalidParam("metadata_rules must not exceed " + MetadataRule.MAX_RULES);
        }
        Set<String> seen = new HashSet<>();
        for (MetadataRule rule : rules) {
            validateMetadataRule(rule);
            if (!seen.add(rule.getKey())) {
                throw BizException.invalidParam("duplicate metadata_rules key: " + rule.getKey());
            }
        }
    }

    private void validateMetadataRule(MetadataRule rule) {
        String key = rule.getKey();
        if (key == null || !MetadataRule.KEY_PATTERN.matcher(key).matches()) {
            throw BizException.invalidParam("invalid metadata_rules key: " + key);
        }
        if (ChunkMetadataKeys.RESERVED.contains(key)) {
            throw BizException.invalidParam("metadata_rules key conflicts with a reserved key: " + key);
        }
        if (MetadataRule.TYPE_CONSTANT.equals(rule.getType())) {
            if (rule.getValue() == null || rule.getValue().isBlank()) {
                throw BizException.invalidParam("constant metadata rule requires a value, key: " + key);
            }
        } else if (MetadataRule.TYPE_REGEX.equals(rule.getType())) {
            validateRegexRule(rule);
        } else if (MetadataRule.TYPE_KEYWORD_MATCH.equals(rule.getType())) {
            validateKeywordRule(rule);
        } else {
            throw BizException.invalidParam("unsupported metadata rule type: " + rule.getType());
        }
    }

    private void validateRegexRule(MetadataRule rule) {
        String pattern = rule.getPattern();
        if (pattern == null || pattern.isBlank() || pattern.length() > MetadataRule.MAX_PATTERN_LENGTH) {
            throw BizException.invalidParam("regex metadata rule requires a pattern of at most "
                    + MetadataRule.MAX_PATTERN_LENGTH + " characters, key: " + rule.getKey());
        }
        try {
            Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw BizException.invalidParam("invalid regex metadata rule pattern, key: " + rule.getKey());
        }
    }

    private void validateKeywordRule(MetadataRule rule) {
        List<String> keywords = rule.getKeywords();
        if (keywords == null || keywords.isEmpty() || keywords.size() > MetadataRule.MAX_KEYWORDS) {
            throw BizException.invalidParam("keyword_match metadata rule requires 1 to "
                    + MetadataRule.MAX_KEYWORDS + " keywords, key: " + rule.getKey());
        }
        for (String keyword : keywords) {
            if (keyword == null || keyword.isBlank() || keyword.length() > MetadataRule.MAX_KEYWORD_LENGTH) {
                throw BizException.invalidParam("each keyword_match keyword must be 1 to "
                        + MetadataRule.MAX_KEYWORD_LENGTH + " characters, key: " + rule.getKey());
            }
        }
    }

    private int orDefault(Integer value, int fallback) {
        return value == null ? fallback : value;
    }
}
