package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.model.KbIndexConfig;
import io.kbrag.domain.model.KbRetrievalConfig;
import io.kbrag.domain.model.ParentChildParams;
import io.kbrag.domain.service.FixedLengthTextSplitter;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * New index configuration of a knowledge base.
 *
 * <p>The payload mirrors the stored {@code index_config} document one for one, so the console can send
 * back what it read without a translation table. The retrieval defaults ride along optionally because
 * an operator usually tunes both in the same sitting, but only the split part feeds the fingerprint:
 * changing a retrieval default cannot invalidate a single stored chunk and must not mark anything for
 * rebuild.
 *
 * @param chunkMaxTokens  maximum estimated tokens per chunk
 * @param chunkOverlap    overlap in estimated tokens
 * @param parentChild     two level splitting parameters
 * @param retrievalConfig knowledge base level retrieval defaults, optional
 *
 * @author owlzhangfq@gmail.com
 */
public record UpdateIndexConfigRequest(
        @JsonProperty("chunk_max_tokens") @NotNull @Min(1) Integer chunkMaxTokens,
        @JsonProperty("chunk_overlap") @NotNull @Min(0) Integer chunkOverlap,
        @JsonProperty("parent_child") ParentChildRequest parentChild,
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
    }

    private int orDefault(Integer value, int fallback) {
        return value == null ? fallback : value;
    }
}
