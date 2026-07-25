package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * Retrieval payload.
 *
 * <p>Both tuning parameters are optional; the service substitutes the configured defaults of 50
 * candidates per route and 5 returned nodes and clamps them to the configured bounds.
 *
 * @param query      user query
 * @param recallTopK candidates recalled per route, {@code null} for the default
 * @param topN       number of returned nodes, {@code null} for the default
 */
public record SearchRequest(
        @NotBlank(message = "must not be blank") String query,
        @JsonProperty("recall_top_k") Integer recallTopK,
        @JsonProperty("top_n") Integer topN) {
}
