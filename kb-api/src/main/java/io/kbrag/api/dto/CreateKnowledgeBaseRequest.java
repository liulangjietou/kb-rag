package io.kbrag.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Knowledge base creation payload.
 *
 * @param name        display name
 * @param description free text description
 *
 * @author owlzhangfq@gmail.com
 */
public record CreateKnowledgeBaseRequest(
        @NotBlank(message = "must not be blank") @Size(max = 128, message = "must be at most 128 characters")
        String name,
        @Size(max = 1024, message = "must be at most 1024 characters") String description) {
}
