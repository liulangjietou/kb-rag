package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.domain.model.EvalEvidence;

/**
 * One stored evidence anchor.
 *
 * @param docId              document business id
 * @param span               exact text excerpt, {@code null} for a document anchored case
 * @param annotatedVersionId document version the excerpt was copied from, provenance only
 *
 * @author owlzhangfq@gmail.com
 */
public record EvalEvidenceResponse(
        @JsonProperty("doc_id") String docId,
        String span,
        @JsonProperty("annotated_version_id") String annotatedVersionId) {

    /**
     * Maps a domain evidence onto its response.
     *
     * @param evidence domain evidence
     * @return response
     */
    public static EvalEvidenceResponse from(EvalEvidence evidence) {
        return new EvalEvidenceResponse(evidence.getDocId(), evidence.getSpan(), evidence.getAnnotatedVersionId());
    }
}
