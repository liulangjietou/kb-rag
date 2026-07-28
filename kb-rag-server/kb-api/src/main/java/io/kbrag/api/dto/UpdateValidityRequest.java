package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Payload of {@code PUT /api/v1/documents/{docId}/validity}.
 *
 * <p>Both bounds are ISO local date time literals and both are optional: a {@code null} clears the
 * bound, so submitting an empty payload removes the window entirely. The controller parses the
 * literals so a malformed timestamp is rejected before the service is involved.
 *
 * @param effectiveAt lower bound, {@code null} clears it
 * @param expiresAt   upper bound, {@code null} clears it
 *
 * @author owlzhangfq@gmail.com
 */
public record UpdateValidityRequest(
        @JsonProperty("effective_at") String effectiveAt,
        @JsonProperty("expires_at") String expiresAt) {
}
