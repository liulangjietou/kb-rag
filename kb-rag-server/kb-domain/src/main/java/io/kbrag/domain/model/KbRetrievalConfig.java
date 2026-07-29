package io.kbrag.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Typed view of the {@code t_kb_knowledge_base.retrieval_config} JSON column.
 *
 * <p>Layer three of the configuration model for the retrieval side: every field is nullable and a
 * {@code null} simply falls through to the deployment default, so a knowledge base only records the
 * values an operator deliberately changed. The request layer still wins over everything here, which
 * is what keeps the debug console able to try a parameter without persisting it.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class KbRetrievalConfig {

    /** Candidates recalled per route. */
    @JsonProperty("recall_top_k")
    private Integer recallTopK;

    /** Number of returned nodes. */
    @JsonProperty("top_n")
    private Integer topN;

    /** Fusion strategy literal, {@code rrf} or {@code weighted}. */
    @JsonProperty("fusion_mode")
    private String fusionMode;

    /** Vector weight of the weighted strategy. */
    @JsonProperty("w_vec")
    private Double wVec;

    /** Damping constant of the reciprocal rank strategy. */
    @JsonProperty("rrf_k")
    private Integer rrfK;

    /** Absolute score threshold, {@code null} disables filtering. */
    @JsonProperty("score_threshold")
    private Double scoreThreshold;

    /** Rerank switch. */
    @JsonProperty("rerank_enabled")
    private Boolean rerankEnabled;

    /**
     * Rerank ordering mode, the M14 contract section 5. {@code null} falls through to the deployment
     * default {@code semantic}, so a base configured before M14 keeps the pure semantic ordering.
     */
    @JsonProperty("rerank_mode")
    private String rerankMode;

    /**
     * Semantic weight of the {@code hybrid} rerank mode, within {@code [0,1]}, the M14 contract section
     * 5. The BM25 weight is its complement. {@code null} falls through to the deployment default.
     */
    @JsonProperty("rerank_w_semantic")
    private Double rerankWSemantic;

    /** Query rewrite switch. */
    @JsonProperty("rewrite_enabled")
    private Boolean rewriteEnabled;

    /**
     * Graph route switch of this knowledge base, requirement section 4.9. {@code null} and {@code false}
     * both mean off, which is what keeps a base configured before M7 out of the graph pipeline.
     *
     * <p>Mutually exclusive with {@code fusion_mode=weighted}: the graph relevance and the two semantic
     * scores have no common scale, so a weighted sum over three routes cannot be defined. The rule is
     * enforced in one place, {@code GraphFusionPolicy}, and never re-checked downstream.
     */
    @JsonProperty("graph_enabled")
    private Boolean graphEnabled;

    /**
     * Tells whether the graph route is switched on for this knowledge base.
     *
     * @return {@code true} only when an operator explicitly enabled it
     */
    public boolean graphEnabled() {
        return Boolean.TRUE.equals(graphEnabled);
    }
}
