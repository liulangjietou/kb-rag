package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.domain.entity.ExtSource;

import java.time.LocalDateTime;

/**
 * External source registration view, the M14 contract section 2.3.
 *
 * <p>The secret never leaves the server: the field always carries the fixed mask, and the update
 * endpoint treats a blank secret as "keep the stored one" so this view can round trip through an
 * edit form without destroying the credential.
 *
 * @param sourceId       business identifier
 * @param kbId           knowledge base the fetched objects land in
 * @param sourceType     connector type routing key
 * @param name           operator facing display name
 * @param endpoint       remote service endpoint
 * @param region         optional connector-specific region hint
 * @param bucket         bucket name or Confluence space key
 * @param prefix         optional connector-specific listing prefix
 * @param accessKey      access key or Atlassian account email
 * @param secretKey      fixed mask, never the stored value
 * @param syncEnabled    whether the scheduled pass includes this source
 * @param lastSyncStatus outcome of the last sync pass, {@code null} before the first one
 * @param lastSyncAt     ISO instant of the last sync attempt
 * @param lastError      why the last sync failed or was partial, {@code null} on success
 * @param createdAt      ISO registration timestamp
 *
 * @author owlzhangfq@gmail.com
 */
public record ExtSourceResponse(
        @JsonProperty("source_id") String sourceId,
        @JsonProperty("kb_id") String kbId,
        @JsonProperty("source_type") String sourceType,
        String name,
        String endpoint,
        String region,
        String bucket,
        String prefix,
        @JsonProperty("access_key") String accessKey,
        @JsonProperty("secret_key") String secretKey,
        @JsonProperty("sync_enabled") boolean syncEnabled,
        @JsonProperty("last_sync_status") String lastSyncStatus,
        @JsonProperty("last_sync_at") String lastSyncAt,
        @JsonProperty("last_error") String lastError,
        @JsonProperty("created_at") String createdAt) {

    /** The only value the secret field ever carries on the way out. */
    public static final String SECRET_MASK = "******";

    private static final int SYNC_ON = 1;

    /**
     * Maps an entity onto its view, masking the secret.
     *
     * @param entity source entity
     * @return view
     */
    public static ExtSourceResponse from(ExtSource entity) {
        return new ExtSourceResponse(
                entity.getSourceId(),
                entity.getKbId(),
                entity.getSourceType(),
                entity.getName(),
                entity.getEndpoint(),
                entity.getRegion(),
                entity.getBucket(),
                entity.getPrefix(),
                entity.getAccessKey(),
                SECRET_MASK,
                entity.getSyncEnabled() != null && entity.getSyncEnabled() == SYNC_ON,
                entity.getLastSyncStatus() == null ? null : entity.getLastSyncStatus().name(),
                iso(entity.getLastSyncAt()),
                entity.getLastError(),
                iso(entity.getCreatedAt()));
    }

    private static String iso(LocalDateTime value) {
        return value == null ? null : value.toString();
    }
}
