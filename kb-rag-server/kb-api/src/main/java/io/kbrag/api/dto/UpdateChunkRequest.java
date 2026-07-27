package io.kbrag.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * New text of a chunk.
 *
 * @param content replacement text, blank rejected because an empty chunk can never be recalled and
 *                would silently remove the passage instead of disabling it
 *
 * @author owlzhangfq@gmail.com
 */
public record UpdateChunkRequest(@NotBlank String content) {
}
