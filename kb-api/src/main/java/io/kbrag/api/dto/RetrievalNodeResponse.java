package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.app.retrieval.RetrievalNodeView;

import java.util.List;
import java.util.Map;

/**
 * Transport shape of the shared {@code RetrievalNode} schema.
 *
 * @param docId             owning document
 * @param documentVersionId owning document version
 * @param chunkId           chunk business id
 * @param chunkType         content nature, {@code text}, {@code image} or {@code chat_log}
 * @param content           chunk text
 * @param score             score of the route that ranked this node best
 * @param scoreType         nature of {@code score}, {@code cosine} or {@code bm25_rank}
 * @param retrievalSource   route that ranked this node best, {@code vector} or {@code bm25}
 * @param metadata          per route scores and ranks plus the fusion score
 * @param imageUrls         pre signed image URLs, empty in M1
 * @param previewUrl        pre signed page snapshot URL, {@code null} in M1
 */
public record RetrievalNodeResponse(
        @JsonProperty("doc_id") String docId,
        @JsonProperty("document_version_id") String documentVersionId,
        @JsonProperty("chunk_id") String chunkId,
        @JsonProperty("chunk_type") String chunkType,
        String content,
        double score,
        @JsonProperty("score_type") String scoreType,
        @JsonProperty("retrieval_source") String retrievalSource,
        Map<String, Object> metadata,
        @JsonProperty("image_urls") List<String> imageUrls,
        @JsonProperty("preview_url") String previewUrl) {

    /**
     * Maps an application view onto the transport shape.
     *
     * @param view application view
     * @return transport node
     */
    public static RetrievalNodeResponse from(RetrievalNodeView view) {
        return new RetrievalNodeResponse(
                view.getDocId(),
                view.getDocumentVersionId(),
                view.getChunkId(),
                view.getChunkType(),
                view.getContent(),
                view.getScore(),
                view.getScoreType(),
                view.getRetrievalSource(),
                view.getMetadata(),
                view.getImageUrls(),
                view.getPreviewUrl());
    }
}
