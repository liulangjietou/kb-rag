package io.kbrag.api.dto;

import jakarta.validation.constraints.NotNull;

/**
 * New retrieval switch value of a chunk.
 *
 * @param enabled {@code false} keeps the chunk out of every recall route
 *
 * @author owlzhangfq@gmail.com
 */
public record ToggleChunkRequest(@NotNull Boolean enabled) {
}
