package io.kbrag.api.dto;

import jakarta.validation.constraints.Size;

/**
 * Application rename and description payload; a {@code null} field keeps the stored value.
 *
 * @param name        new display name
 * @param description new description
 *
 * @author owlzhangfq@gmail.com
 */
public record UpdateAppRequest(
        @Size(max = 128, message = "must be at most 128 characters") String name,
        @Size(max = 1024, message = "must be at most 1024 characters") String description) {
}
