package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.domain.entity.ExtSourceItem;

import java.time.LocalDateTime;

/**
 * Per object sync outcome view of one external source, the M14 contract section 2.3.
 *
 * @param objectKey  object key inside the bucket
 * @param etag       change marker of the last ingested body, {@code null} before the first ingest
 * @param docId      document the object feeds, {@code null} until the first successful ingest
 * @param lastStatus outcome of the last sync visit, {@code null} before the first one
 * @param lastError  why the last visit failed or was skipped, {@code null} on success
 * @param lastSyncAt ISO instant of the last sync visit
 * @param createdAt  ISO instant the object was first discovered
 *
 * @author owlzhangfq@gmail.com
 */
public record ExtSourceItemResponse(
        @JsonProperty("object_key") String objectKey,
        String etag,
        @JsonProperty("doc_id") String docId,
        @JsonProperty("last_status") String lastStatus,
        @JsonProperty("last_error") String lastError,
        @JsonProperty("last_sync_at") String lastSyncAt,
        @JsonProperty("created_at") String createdAt) {

    /**
     * Maps an entity onto its view.
     *
     * @param entity item entity
     * @return view
     */
    public static ExtSourceItemResponse from(ExtSourceItem entity) {
        return new ExtSourceItemResponse(
                entity.getObjectKey(),
                entity.getEtag(),
                entity.getDocId(),
                entity.getLastStatus() == null ? null : entity.getLastStatus().name(),
                entity.getLastError(),
                iso(entity.getLastSyncAt()),
                iso(entity.getCreatedAt()));
    }

    private static String iso(LocalDateTime value) {
        return value == null ? null : value.toString();
    }
}
