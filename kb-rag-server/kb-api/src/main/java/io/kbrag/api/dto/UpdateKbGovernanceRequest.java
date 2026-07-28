package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

/**
 * Payload of {@code PUT /api/v1/kb/{kbId}/governance}: the review switch of a knowledge base.
 *
 * @param reviewRequired {@code true} makes future uploads start as DRAFT instead of PUBLISHED
 *
 * @author owlzhangfq@gmail.com
 */
public record UpdateKbGovernanceRequest(
        @JsonProperty("review_required") @NotNull(message = "must not be null") Boolean reviewRequired) {
}
