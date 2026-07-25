package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.domain.entity.Chunk;

/**
 * Chunk view of the chunk drawer.
 *
 * @param chunkId           business identifier
 * @param documentVersionId owning document version
 * @param seq               order inside the version
 * @param chunkType         content nature
 * @param content           chunk text, rendered as plain text by the console
 * @param chunkTextHash     normalised text digest
 * @param enabled           {@code true} when the chunk participates in retrieval
 * @param embeddingStatus   embedding lifecycle state
 *
 * @author owlzhangfq@gmail.com
 */
public record ChunkResponse(
        @JsonProperty("chunk_id") String chunkId,
        @JsonProperty("document_version_id") String documentVersionId,
        Integer seq,
        @JsonProperty("chunk_type") String chunkType,
        String content,
        @JsonProperty("chunk_text_hash") String chunkTextHash,
        boolean enabled,
        @JsonProperty("embedding_status") String embeddingStatus) {

    private static final int ENABLED = 1;

    /**
     * Maps an entity onto its view.
     *
     * @param entity chunk entity
     * @return view
     */
    public static ChunkResponse from(Chunk entity) {
        return new ChunkResponse(
                entity.getChunkId(),
                entity.getDocumentVersionId(),
                entity.getSeq(),
                entity.getChunkType() == null ? null : entity.getChunkType().code(),
                entity.getContent(),
                entity.getChunkTextHash(),
                entity.getEnabled() != null && entity.getEnabled() == ENABLED,
                entity.getEmbeddingStatus() == null ? null : entity.getEmbeddingStatus().name());
    }
}
