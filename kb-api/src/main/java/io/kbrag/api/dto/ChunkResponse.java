package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.entity.Chunk;

import java.util.Map;

/**
 * Chunk view of the chunk drawer.
 *
 * <p>The metadata document travels as a parsed object rather than as the raw column string, so the console
 * can label a chat window with its conversation and its senders without parsing a column layout of its own.
 *
 * @param chunkId           business identifier
 * @param documentVersionId owning document version
 * @param seq               order inside the version
 * @param chunkType         content nature
 * @param content           chunk text, rendered as plain text by the console
 * @param chunkTextHash     normalised text digest
 * @param enabled           {@code true} when the chunk participates in retrieval
 * @param embeddingStatus   embedding lifecycle state
 * @param metadata          stored metadata document, {@code null} for a chunk that carries none
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
        @JsonProperty("embedding_status") String embeddingStatus,
        Map<String, Object> metadata) {

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
                entity.getEmbeddingStatus() == null ? null : entity.getEmbeddingStatus().name(),
                parseMetadata(entity.getMetadata()));
    }

    /**
     * Parses the metadata column.
     *
     * @param json stored document, may be blank
     * @return parsed document, {@code null} when the column carries nothing
     */
    private static Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        return JsonUtil.parse(json, new TypeReference<Map<String, Object>>() {
        });
    }
}
