package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.app.annotation.AnnotationInheritanceService;
import io.kbrag.domain.service.AnnotationMigrationAdvisor;

import java.util.List;
import java.util.Map;

/**
 * One entry of the review list a new document version leaves behind.
 *
 * <p>The endpoint returns only entries that genuinely await a decision, so the console can use the
 * length of the list as the number it shows without filtering again.
 *
 * <p><b>Shape.</b> Every column of the stored annotation is reported, so the console binds one
 * annotation type here and against any future endpoint that returns stored rows. {@code version} and
 * {@code excerpt} are the only derived fields: the row references its version by id alone, and the text
 * excerpt sits under a key that differs per operation kind, which the console should not have to know.
 *
 * <p>{@code inherit_status} is the stored value. The current review filter only admits
 * {@code NOT_INHERITED} rows, so it is constant in this response today; it is reported because it is the
 * value the console renders as a tag, and because widening the filter later must not change its meaning.
 *
 * @param annotationId      business identifier
 * @param kbId              owning knowledge base
 * @param docId             owning document, stable across versions
 * @param documentVersionId version the operation was performed against
 * @param version           version number of that version, {@code null} when it is gone
 * @param chunkId           chunk the operation named
 * @param annotationType    operation kind
 * @param payload           full operation detail
 * @param excerpt           original text excerpt the operator has to recognise
 * @param chunkTextHash     normalised text digest that drives the cross version inheritance
 * @param inheritStatus     cross version state of the annotation
 * @param operator          console account that performed the operation
 * @param createdAt         ISO timestamp of the operation
 * @param suggestions       chunks of the active version this annotation could be moved to, always present
 *                          and empty when nothing is similar enough - a console binding a list must never
 *                          have to branch on a missing field
 *
 * @author owlzhangfq@gmail.com
 */
public record PendingAnnotationResponse(
        @JsonProperty("annotation_id") String annotationId,
        @JsonProperty("kb_id") String kbId,
        @JsonProperty("doc_id") String docId,
        @JsonProperty("document_version_id") String documentVersionId,
        String version,
        @JsonProperty("chunk_id") String chunkId,
        @JsonProperty("annotation_type") String annotationType,
        Map<String, Object> payload,
        String excerpt,
        @JsonProperty("chunk_text_hash") String chunkTextHash,
        @JsonProperty("inherit_status") String inheritStatus,
        String operator,
        @JsonProperty("created_at") String createdAt,
        List<MigrationSuggestionResponse> suggestions) {

    /**
     * Maps a view onto its response.
     *
     * @param pending review item
     * @return response
     */
    public static PendingAnnotationResponse from(AnnotationInheritanceService.PendingAnnotation pending) {
        return new PendingAnnotationResponse(
                pending.annotationId(),
                pending.kbId(),
                pending.docId(),
                pending.documentVersionId(),
                pending.version(),
                pending.chunkId(),
                pending.annotationType(),
                pending.payload(),
                pending.excerpt(),
                pending.chunkTextHash(),
                pending.inheritStatus(),
                pending.operator(),
                pending.createdAt(),
                pending.suggestions() == null ? List.of()
                        : pending.suggestions().stream().map(MigrationSuggestionResponse::from).toList());
    }

    /**
     * One chunk of the active version an annotation could be moved to.
     *
     * @param chunkId        chunk business id, the value the migrate endpoint takes
     * @param contentPreview leading characters of the chunk, at most 120, so an operator can recognise it
     * @param score          symmetric Dice similarity between {@code 0.0} and {@code 1.0}
     */
    public record MigrationSuggestionResponse(
            @JsonProperty("chunk_id") String chunkId,
            @JsonProperty("content_preview") String contentPreview,
            double score) {

        /**
         * Maps a recommendation onto its response.
         *
         * @param suggestion recommendation
         * @return response
         */
        public static MigrationSuggestionResponse from(AnnotationMigrationAdvisor.Suggestion suggestion) {
            return new MigrationSuggestionResponse(suggestion.chunkId(), suggestion.contentPreview(),
                    suggestion.score());
        }
    }
}
