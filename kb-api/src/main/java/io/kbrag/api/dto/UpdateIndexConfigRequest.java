package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.model.ChatAggregationParams;
import io.kbrag.domain.model.CleanRules;
import io.kbrag.domain.model.KbIndexConfig;
import io.kbrag.domain.model.KbRetrievalConfig;
import io.kbrag.domain.model.ParentChildParams;
import io.kbrag.domain.service.FixedLengthTextSplitter;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

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
 * @param chunkMaxTokens       maximum estimated tokens per chunk
 * @param chunkOverlap         overlap in estimated tokens
 * @param parentChild          two level splitting parameters
 * @param cleanRules           cleaning rules, {@code null} keeps the stored ones
 * @param parsePreviewRequired parse preview switch, {@code null} keeps the stored value
 * @param chatAggregation      chat import window, {@code null} keeps the stored one
 * @param retrievalConfig      knowledge base level retrieval defaults, optional
 *
 * @author owlzhangfq@gmail.com
 */
public record UpdateIndexConfigRequest(
        @JsonProperty("chunk_max_tokens") @NotNull @Min(1) Integer chunkMaxTokens,
        @JsonProperty("chunk_overlap") @NotNull @Min(0) Integer chunkOverlap,
        @JsonProperty("parent_child") ParentChildRequest parentChild,
        @JsonProperty("clean_rules") CleanRules cleanRules,
        @JsonProperty("parse_preview_required") Boolean parsePreviewRequired,
        @JsonProperty("chat_aggregation") ChatAggregationParams chatAggregation,
        @JsonProperty("retrieval_config") KbRetrievalConfig retrievalConfig) {

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
        config.setSplitStrategy(current.getSplitStrategy() == null
                ? FixedLengthTextSplitter.STRATEGY_CODE : current.getSplitStrategy());
        config.setEmbeddingModel(current.getEmbeddingModel());
        config.setChunkMaxTokens(chunkMaxTokens);
        config.setChunkOverlap(chunkOverlap);
        config.setParentChild(toParentChild(current.parentChildOrDisabled()));
        config.setCleanRules(cleanRules == null ? current.cleanRulesOrDefaults() : cleanRules);
        config.setParsePreviewRequired(parsePreviewRequired == null
                ? current.isParsePreviewRequired() : parsePreviewRequired);
        config.setChatAggregation(chatAggregation == null
                ? current.chatAggregationOrDefaults() : chatAggregation);
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
        validateCleaning(config);
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

    private int orDefault(Integer value, int fallback) {
        return value == null ? fallback : value;
    }
}
