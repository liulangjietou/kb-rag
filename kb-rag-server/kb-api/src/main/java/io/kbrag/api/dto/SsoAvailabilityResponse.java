package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Whether the login page should offer the single sign-on entry point.
 *
 * @param available {@code true} when the directory integration is configured and switched on
 *
 * @author owlzhangfq@gmail.com
 */
public record SsoAvailabilityResponse(
        @JsonProperty("sso_available") boolean available) {
}
