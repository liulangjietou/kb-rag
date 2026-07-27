package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * Target of an assisted annotation migration.
 *
 * <p>One chunk and nothing else: the endpoint deliberately takes no list and no "apply the best
 * suggestion" flag, because the whole point of the feature is that a human names the target.
 *
 * @param targetChunkId chunk of the active version the annotation is applied to
 *
 * @author owlzhangfq@gmail.com
 */
public record MigrateAnnotationRequest(
        @JsonProperty("target_chunk_id")
        @NotBlank(message = "must not be blank") String targetChunkId) {
}
