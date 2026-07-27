package io.kbrag.domain.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.domain.enums.EvalMode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * One row of the configuration matrix an evaluation run submission compares, stored verbatim as
 * {@code t_kb_eval_run.retrieval_config} once the run is created.
 *
 * <p>Kept as one JSON document rather than split into columns because it is exactly the shape the
 * report page renders back for one column of the comparison table, {@code label} and {@code mode}
 * included; a caller reading the run does not have to reassemble it from several fields.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@NoArgsConstructor
@ToString
public class EvalRetrievalConfig {

    /** Display label of this configuration column, chosen by the caller. */
    private String label;

    /** Retrieval mode this configuration maps onto. */
    private EvalMode mode;

    /** Candidates recalled per route, {@code null} keeps the deployment default. */
    @JsonProperty("recall_top_k")
    private Integer recallTopK;

    /** Number of returned units, also the metrics {@code K} of this run. */
    @JsonProperty("top_n")
    private Integer topN;

    /** Fusion strategy literal, {@code null} keeps the deployment default. */
    private String fusion;

    /** Absolute score threshold, {@code null} disables filtering. */
    @JsonProperty("score_threshold")
    private Double scoreThreshold;

    /** Query rewrite switch, {@code null} keeps the deployment default. */
    @JsonProperty("rewrite_enabled")
    private Boolean rewriteEnabled;
}
