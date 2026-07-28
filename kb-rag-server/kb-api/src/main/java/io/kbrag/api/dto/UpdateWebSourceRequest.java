package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

/**
 * Payload of {@code PUT /api/v1/web-sources/{sourceId}}: the sync switch is the only mutable
 * attribute of a registration - changing the URL would change its identity, so that is a remove
 * plus a new registration instead.
 *
 * @param syncEnabled {@code true} includes the source in the scheduled sync pass
 *
 * @author owlzhangfq@gmail.com
 */
public record UpdateWebSourceRequest(
        @JsonProperty("sync_enabled") @NotNull(message = "must not be null") Boolean syncEnabled) {
}
