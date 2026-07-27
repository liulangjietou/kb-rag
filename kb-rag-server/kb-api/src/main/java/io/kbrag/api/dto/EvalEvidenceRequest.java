package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.domain.model.EvalEvidence;
import jakarta.validation.constraints.NotBlank;

/**
 * One evidence anchor as submitted by a caller; {@code annotated_version_id} is never accepted here,
 * the service fills it from the target document's current active version.
 *
 * @param docId document business id the evidence belongs to
 * @param span  exact text excerpt, required for a {@code SPAN} anchored case
 *
 * @author owlzhangfq@gmail.com
 */
public record EvalEvidenceRequest(
        @JsonProperty("doc_id") @NotBlank(message = "must not be blank") String docId,
        String span) {

    /**
     * Maps the transport shape onto the domain model, leaving provenance for the service to resolve.
     *
     * @return domain evidence
     */
    public EvalEvidence toEvidence() {
        EvalEvidence evidence = new EvalEvidence();
        evidence.setDocId(docId);
        evidence.setSpan(span);
        return evidence;
    }
}
