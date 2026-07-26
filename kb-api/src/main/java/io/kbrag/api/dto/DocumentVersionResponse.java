package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.app.document.DocumentVersionService;
import io.kbrag.domain.entity.DocumentVersion;

/**
 * One row of the version management drawer.
 *
 * @param versionId    business identifier
 * @param version      version number in {@code major.minor} form
 * @param status       build lifecycle state
 * @param contentHash  digest of the original byte stream
 * @param chunkCount   chunks the version still owns
 * @param active       {@code true} when the document currently serves this version
 * @param rollbackMode {@code INSTANT} when activating it is a pointer switch, {@code REBUILD} otherwise
 * @param changelog    change note
 * @param createdAt    ISO creation timestamp
 *
 * @author owlzhangfq@gmail.com
 */
public record DocumentVersionResponse(
        @JsonProperty("version_id") String versionId,
        String version,
        String status,
        @JsonProperty("content_hash") String contentHash,
        @JsonProperty("chunk_count") long chunkCount,
        boolean active,
        @JsonProperty("rollback_mode") String rollbackMode,
        String changelog,
        @JsonProperty("created_at") String createdAt) {

    /**
     * Maps a view onto its response.
     *
     * @param view version view
     * @return response
     */
    public static DocumentVersionResponse from(DocumentVersionService.VersionView view) {
        DocumentVersion entity = view.version();
        return new DocumentVersionResponse(
                entity.getVersionId(),
                entity.getVersion(),
                entity.getStatus() == null ? null : entity.getStatus().name(),
                entity.getContentHash(),
                view.chunkCount(),
                view.active(),
                view.rollbackMode().name(),
                entity.getChangelog(),
                entity.getCreatedAt() == null ? null : entity.getCreatedAt().toString());
    }
}
