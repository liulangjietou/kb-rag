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

    /** Query rewrite switch. */
    @JsonProperty("rewrite_enabled")
    private Boolean rewriteEnabled;
}
