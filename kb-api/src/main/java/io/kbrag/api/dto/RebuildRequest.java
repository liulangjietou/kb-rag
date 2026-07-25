package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Rebuild scope.
 *
 * <p>An absent or empty list means every document the current configuration invalidated, which is the
 * normal case: an operator changes the split parameters and then rebuilds what the change affected,
 * without having to enumerate documents.
 *
 * @param docIds explicit document scope
 */
public record RebuildRequest(@JsonProperty("doc_ids") List<String> docIds) {
}
