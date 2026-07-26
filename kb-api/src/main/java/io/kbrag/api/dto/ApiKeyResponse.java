package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.domain.entity.ApiKey;

import java.util.List;

/**
 * API key payload of the list and detail views.
 *
 * <p><b>No key material.</b> The digest exists on the entity and must never reach a response, which is the
 * reason this record exists at all rather than the entity being serialised: {@link #prefix} is a display form
 * an operator can recognise their key by, and nothing more.
 *
 * @param keyId      business identifier
 * @param name       display name of the caller it was issued to
 * @param prefix     display form, leading segment plus the last four characters
 * @param status     {@code ENABLED} or {@code DISABLED}
 * @param qpsLimit   token bucket rate
 * @param appScope   allowed application ids; empty authorises every application
 * @param lastUsedAt ISO timestamp of the last successful authentication
 * @param createdAt  ISO creation timestamp
 *
 * @author owlzhangfq@gmail.com
 */
public record ApiKeyResponse(
        @JsonProperty("key_id") String keyId,
        String name,
        String prefix,
        String status,
        @JsonProperty("qps_limit") Integer qpsLimit,
        @JsonProperty("app_scope") List<String> appScope,
        @JsonProperty("last_used_at") String lastUsedAt,
        @JsonProperty("created_at") String createdAt) {

    /**
     * Maps a stored key onto its response.
     *
     * @param key      stored key
     * @param appScope parsed authorisation scope
     * @return response
     */
    public static ApiKeyResponse from(ApiKey key, List<String> appScope) {
        return new ApiKeyResponse(
                key.getKeyId(),
                key.getName(),
                key.getPrefix(),
                key.getStatus() == null ? null : key.getStatus().name(),
                key.getQpsLimit(),
                appScope,
                key.getLastUsedAt() == null ? null : key.getLastUsedAt().toString(),
                key.getCreatedAt() == null ? null : key.getCreatedAt().toString());
    }
}
