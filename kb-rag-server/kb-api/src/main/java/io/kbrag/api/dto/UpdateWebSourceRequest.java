package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Payload of {@code PUT /api/v1/web-sources/{sourceId}}, the M17 contract section 3.3: the mutable
 * switches of a registration - scheduled sync and JS rendering. Both are optional and only the
 * present ones are applied, so the console's two toggles share one endpoint; the URL is not mutable
 * here because changing it changes the identity, which is a remove plus a new registration instead.
 *
 * @param syncEnabled new scheduled-sync value, {@code null} leaves it unchanged
 * @param renderJs    new JS-render value, {@code null} leaves it unchanged
 *
 * @author owlzhangfq@gmail.com
 */
public record UpdateWebSourceRequest(
        @JsonProperty("sync_enabled") Boolean syncEnabled,
        @JsonProperty("render_js") Boolean renderJs) {
}
