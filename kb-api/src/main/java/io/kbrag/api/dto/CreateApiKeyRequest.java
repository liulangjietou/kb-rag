package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * API key creation payload.
 *
 * @param name     display name of the caller the key is issued to
 * @param qpsLimit token bucket rate, {@code null} takes the deployment default
 * @param appScope allowed application ids; empty or {@code null} authorises every application
 *
 * @author owlzhangfq@gmail.com
 */
public record CreateApiKeyRequest(
        @NotBlank(message = "must not be blank") @Size(max = 128, message = "must be at most 128 characters")
        String name,
        @JsonProperty("qps_limit") @Min(value = 1, message = "must be at least 1") Integer qpsLimit,
        @JsonProperty("app_scope") List<String> appScope) {
}
