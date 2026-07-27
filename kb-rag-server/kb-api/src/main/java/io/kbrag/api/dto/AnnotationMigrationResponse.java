package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.app.annotation.AnnotationMigrationService;

import java.util.List;

/**
 * Outcome of an assisted annotation migration.
 *
 * <p>The console only needs the call to succeed - it reloads the review list afterwards - so the body is
 * informational: it names what was replayed and which chunks actually changed, which is what makes a
 * repeated call recognisable in a log rather than indistinguishable from the first one.
 *
 * @param annotationId    annotation that was migrated
 * @param targetChunkId   chunk it was applied to
 * @param annotationType  operation kind that was replayed
 * @param inheritStatus   state the annotation is in afterwards
 * @param changedChunkIds chunks the replay actually modified, empty on a repeated call
 * @param alreadyMigrated {@code true} when the review item was already closed before this call
 *
 * @author owlzhangfq@gmail.com
 */
public record AnnotationMigrationResponse(
        @JsonProperty("annotation_id") String annotationId,
        @JsonProperty("target_chunk_id") String targetChunkId,
        @JsonProperty("annotation_type") String annotationType,
        @JsonProperty("inherit_status") String inheritStatus,
        @JsonProperty("changed_chunk_ids") List<String> changedChunkIds,
        @JsonProperty("already_migrated") boolean alreadyMigrated) {

    /**
     * Maps a result onto its response.
     *
     * @param result migration outcome
     * @return response
     */
    public static AnnotationMigrationResponse from(AnnotationMigrationService.MigrationResult result) {
        return new AnnotationMigrationResponse(result.annotationId(), result.targetChunkId(),
                result.annotationType(), result.inheritStatus(), result.changedChunkIds(),
                result.alreadyMigrated());
    }
}
