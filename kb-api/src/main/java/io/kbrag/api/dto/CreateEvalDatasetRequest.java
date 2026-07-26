package io.kbrag.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Payload of {@code POST /api/v1/kb/{kbId}/eval-datasets}.
 *
 * @param name        display name
 * @param description free text description, optional
 *
 * @author owlzhangfq@gmail.com
 */
public record CreateEvalDatasetRequest(
        @NotBlank(message = "must not be blank") String name,
        String description) {
}
