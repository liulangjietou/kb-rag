package io.kbrag.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Application creation payload.
 *
 * <p>Carries no configuration: the configuration form of the console is a version creation, so a new
 * application legitimately starts with no version at all.
 *
 * @param name        display name
 * @param description free text description
 *
 * @author owlzhangfq@gmail.com
 */
public record CreateAppRequest(
        @NotBlank(message = "must not be blank") @Size(max = 128, message = "must be at most 128 characters")
        String name,
        @Size(max = 1024, message = "must be at most 1024 characters") String description) {
}
