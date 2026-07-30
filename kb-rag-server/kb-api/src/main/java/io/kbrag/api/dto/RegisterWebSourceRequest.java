package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload of {@code POST /api/v1/kb/{kbId}/web-sources}: registers one page URL for import.
 *
 * @param url         page address, http or https
 * @param syncEnabled whether the scheduled pass should keep this page fresh, defaults to on
 * @param renderJs    whether to fetch through a headless browser and store the rendered DOM, defaults to off
 *
 * @author owlzhangfq@gmail.com
 */
public record RegisterWebSourceRequest(
        @NotBlank(message = "must not be blank") @Size(max = 2048, message = "must be at most 2048 characters")
        String url,
        @JsonProperty("sync_enabled") Boolean syncEnabled,
        @JsonProperty("render_js") Boolean renderJs) {
}
