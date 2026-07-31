package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.domain.entity.MemoryAppKey;

import java.time.LocalDateTime;

/**
 * Memory key view, the M19 contract: never carries key material, only the display form.
 *
 * @author owlzhangfq@gmail.com
 */
public record MemoryAppKeyResponse(

        @JsonProperty("key_id")
        String keyId,

        @JsonProperty("library_id")
        String libraryId,

        String name,

        @JsonProperty("key_prefix")
        String keyPrefix,

        String status,

        @JsonProperty("qps_limit")
        Integer qpsLimit,

        @JsonProperty("last_used_at")
        LocalDateTime lastUsedAt,

        @JsonProperty("created_at")
        LocalDateTime createdAt) {

    /**
     * Maps the stored key onto the transport shape.
     *
     * @param key stored key row
     * @return response body
     */
    public static MemoryAppKeyResponse from(MemoryAppKey key) {
        return new MemoryAppKeyResponse(key.getKeyId(), key.getLibraryId(), key.getName(),
                key.getKeyPrefix(), key.getStatus() == null ? null : key.getStatus().name(),
                key.getQpsLimit(), key.getLastUsedAt(), key.getCreatedAt());
    }
}
