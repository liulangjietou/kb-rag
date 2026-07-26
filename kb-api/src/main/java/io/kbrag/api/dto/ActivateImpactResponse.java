package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.app.document.DocumentVersionService;

/**
 * Pre-flight answer of the version switch confirmation dialog.
 *
 * <p>{@code affected_eval_case_count} is present and always zero: evaluation sets belong to a later
 * milestone, and shipping the field now means the console never has to change its confirmation dialog
 * when they arrive.
 *
 * @param rollbackMode          {@code INSTANT} or {@code REBUILD}
 * @param needsRebuild          convenience flag mirroring {@code rollbackMode}
 * @param staleAnnotationCount  manual operations of other versions the target neither inherits nor repeats
 * @param affectedEvalCaseCount evaluation cases that would need a review
 *
 * @author owlzhangfq@gmail.com
 */
public record ActivateImpactResponse(
        @JsonProperty("rollback_mode") String rollbackMode,
        @JsonProperty("needs_rebuild") boolean needsRebuild,
        @JsonProperty("stale_annotation_count") int staleAnnotationCount,
        @JsonProperty("affected_eval_case_count") int affectedEvalCaseCount) {

    /**
     * Maps a view onto its response.
     *
     * @param impact pre-flight summary
     * @return response
     */
    public static ActivateImpactResponse from(DocumentVersionService.ActivateImpact impact) {
        return new ActivateImpactResponse(
                impact.rollbackMode().name(),
                impact.rollbackMode().needsRebuild(),
                impact.staleAnnotationCount(),
                impact.affectedEvalCaseCount());
    }
}
