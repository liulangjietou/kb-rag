package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.app.graph.GraphEntityChunkView;

/**
 * One source passage of an entity, requirement section 4.9 "drill down to the source chunks".
 *
 * <p>A simplified row rather than a {@code RetrievalNode}: there is no query here, so there is no score,
 * no score type and no recall source to report, and filling those with placeholders would leave a console
 * unable to tell a placeholder from a real value. The document name and the version label are resolved
 * server side so the drill down is one request.
 *
 * @param chunkId              chunk business id
 * @param docId                owning document
 * @param docFileName          display name of the owning document
 * @param documentVersionId    owning document version
 * @param documentVersionLabel display label of that version
 * @param content              chunk text, read from the MySQL fact source
 * @param enabled              retrieval switch, so a drill down explains a passage the graph route skips
 *
 * @author owlzhangfq@gmail.com
 */
public record GraphEntityChunkResponse(
        @JsonProperty("chunk_id") String chunkId,
        @JsonProperty("doc_id") String docId,
        @JsonProperty("doc_file_name") String docFileName,
        @JsonProperty("document_version_id") String documentVersionId,
        @JsonProperty("document_version_label") String documentVersionLabel,
        String content,
        boolean enabled) {

    /**
     * Maps an application view onto the transport shape.
     *
     * @param view application view
     * @return transport row
     */
    public static GraphEntityChunkResponse from(GraphEntityChunkView view) {
        return new GraphEntityChunkResponse(view.chunkId(), view.docId(), view.docFileName(),
                view.documentVersionId(), view.documentVersionLabel(), view.content(), view.enabled());
    }
}
