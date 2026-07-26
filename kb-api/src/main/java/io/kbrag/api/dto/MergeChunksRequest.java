package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Chunks to replace by their concatenation.
 *
 * <p>Whether the chunks are actually mergeable - one document version, one parent, consecutive order -
 * is decided by the application service, because those are statements about stored rows rather than
 * about the payload.
 *
 * @param chunkIds chunks to merge, in any order
 *
 * @author owlzhangfq@gmail.com
 */
public record MergeChunksRequest(@JsonProperty("chunk_ids") @NotEmpty List<String> chunkIds) {
}
