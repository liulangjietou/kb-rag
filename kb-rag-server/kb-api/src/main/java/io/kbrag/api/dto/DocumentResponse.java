package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.app.document.UploadOutcome;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.enums.PublishStatus;

import java.time.LocalDateTime;

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
 * @param publishStatus    editorial state, PUBLISHED for every document that predates M11
 * @param reviewNote       latest rejection reason, {@code null} unless the state is REJECTED
 * @param effectiveAt      ISO lower bound of the validity window, {@code null} means unbounded
 * @param expiresAt        ISO upper bound of the validity window, {@code null} means unbounded
 * @param trashedAt        ISO instant the document entered the recycle bin, {@code null} outside it
 * @param createdAt        ISO creation timestamp
 * @param versionId        version the upload produced, only present on the upload response
 * @param version          version number of that version, only present on the upload response
 * @param duplicated       {@code true} when the upload created no version, only on the upload response
 * @param duplicateOfDocId another document of the knowledge base holding the same bytes, upload only
 *
 * @author owlzhangfq@gmail.com
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
        @JsonProperty("publish_status") String publishStatus,
        @JsonProperty("review_note") String reviewNote,
        @JsonProperty("effective_at") String effectiveAt,
        @JsonProperty("expires_at") String expiresAt,
        @JsonProperty("trashed_at") String trashedAt,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("version_id") String versionId,
        String version,
        Boolean duplicated,
        @JsonProperty("duplicate_of_doc_id") String duplicateOfDocId) {

    private static final int STALE = 1;

    /**
     * Maps an entity onto its view.
     *
     * <p>The four upload only fields stay null and are therefore absent from the serialised document,
     * which is what keeps a list response the same shape it has always had.
     *
     * @param entity document entity
     * @return view
     */
    public static DocumentResponse from(Document entity) {
        return of(entity, null, null, null, null);
    }

    /**
     * Maps the outcome of an upload onto its view.
     *
     * @param outcome what the upload did
     * @return view carrying the version and the duplicate hints
     */
    public static DocumentResponse from(UploadOutcome outcome) {
        return of(outcome.document(), outcome.versionId(), outcome.version(), outcome.duplicated(),
                outcome.duplicateOfDocId());
    }

    private static DocumentResponse of(Document entity, String versionId, String version,
                                       Boolean duplicated, String duplicateOfDocId) {
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
                // Rows written before the M11 migration carry a null column and are PUBLISHED by contract.
                entity.getPublishStatus() == null
                        ? PublishStatus.PUBLISHED.name() : entity.getPublishStatus().name(),
                entity.getReviewNote(),
                iso(entity.getEffectiveAt()),
                iso(entity.getExpiresAt()),
                iso(entity.getTrashedAt()),
                entity.getCreatedAt() == null ? null : entity.getCreatedAt().toString(),
                versionId,
                version,
                duplicated,
                duplicateOfDocId);
    }

    private static String iso(LocalDateTime value) {
        return value == null ? null : value.toString();
    }
}
