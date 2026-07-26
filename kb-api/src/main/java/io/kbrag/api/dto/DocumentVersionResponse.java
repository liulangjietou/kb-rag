package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.app.document.DocumentVersionService;
import io.kbrag.domain.entity.DocumentVersion;

import java.util.List;

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
 * @param pinned       {@code true} when an application version's index snapshot references this version, which
 *                     makes the retention cleanup skip it, requirement section 4.1 "archiving protection"
 * @param pinnedBy     application version business ids holding that reference, so the console can explain a
 *                     disabled archive action instead of only greying it out
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
        boolean pinned,
        @JsonProperty("pinned_by") List<String> pinnedBy,
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
                view.pinned(),
                view.pinnedBy(),
                entity.getChangelog(),
                entity.getCreatedAt() == null ? null : entity.getCreatedAt().toString());
    }
}
