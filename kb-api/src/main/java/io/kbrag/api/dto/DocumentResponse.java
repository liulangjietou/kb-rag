package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.domain.entity.Document;

/**
 * Document view.
 *
 * @param docId            business identifier
 * @param kbId             owning knowledge base
 * @param fileName         original file name
 * @param fileExt          lower case extension
 * @param fileSize         size in bytes
 * @param currentVersionId active version, {@code null} until the first build succeeds
 * @param processStatus    processing state
 * @param configStale      {@code true} when the active version used an older configuration
 * @param failReason       classified failure cause
 * @param createdAt        ISO creation timestamp
 */
public record DocumentResponse(
        @JsonProperty("doc_id") String docId,
        @JsonProperty("kb_id") String kbId,
        @JsonProperty("file_name") String fileName,
        @JsonProperty("file_ext") String fileExt,
        @JsonProperty("file_size") Long fileSize,
        @JsonProperty("current_version_id") String currentVersionId,
        @JsonProperty("process_status") String processStatus,
        @JsonProperty("config_stale") boolean configStale,
        @JsonProperty("fail_reason") String failReason,
        @JsonProperty("created_at") String createdAt) {

    private static final int STALE = 1;

    /**
     * Maps an entity onto its view.
     *
     * @param entity document entity
     * @return view
     */
    public static DocumentResponse from(Document entity) {
        return new DocumentResponse(
                entity.getDocId(),
                entity.getKbId(),
                entity.getFileName(),
                entity.getFileExt(),
                entity.getFileSize(),
                entity.getCurrentVersionId(),
                entity.getProcessStatus() == null ? null : entity.getProcessStatus().name(),
                entity.getConfigStale() != null && entity.getConfigStale() == STALE,
                entity.getFailReason(),
                entity.getCreatedAt() == null ? null : entity.getCreatedAt().toString());
    }
}
