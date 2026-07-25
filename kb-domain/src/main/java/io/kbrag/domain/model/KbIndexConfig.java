package io.kbrag.domain.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

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
        return SplitParams.of(chunkMaxTokens, chunkOverlap);
    }
}
