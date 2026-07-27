package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import io.kbrag.app.document.DocumentService;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.entity.Chunk;

import java.util.List;
import java.util.Map;

/**
 * Chunk view of the annotation workbench.
 *
 * <p>The metadata document travels as a parsed object rather than as the raw column string, so the console
 * can label a chat window with its conversation and its senders without parsing a column layout of its own.
 *
 * <p>{@code parent_id} and {@code disabled_child_ids} are both present because the workbench has to show
 * the two level structure. The disabled children are computed over the whole document version rather than
 * derived from the returned page: a parent and its children can fall on either side of a page boundary,
 * and a warning that disappears when the operator turns the page is worse than no warning.
 *
 * @param chunkId           business identifier
 * @param documentVersionId owning document version
 * @param parentId          parent chunk, {@code null} for a single level chunk or for a parent itself
 * @param seq               order inside the version
 * @param chunkType         content nature
 * @param content           chunk text, rendered as plain text by the console
 * @param chunkTextHash     normalised text digest
 * @param enabled           {@code true} when the chunk participates in retrieval
 * @param disabledChildIds  children of this chunk that are excluded from retrieval
 * @param embeddingStatus   embedding lifecycle state
 * @param metadata          stored metadata document, {@code null} for a chunk that carries none
 *
 * @author owlzhangfq@gmail.com
 */
public record ChunkResponse(
        @JsonProperty("chunk_id") String chunkId,
        @JsonProperty("document_version_id") String documentVersionId,
        @JsonProperty("parent_id") String parentId,
        Integer seq,
        @JsonProperty("chunk_type") String chunkType,
        String content,
        @JsonProperty("chunk_text_hash") String chunkTextHash,
        boolean enabled,
        @JsonProperty("disabled_child_ids") List<String> disabledChildIds,
        @JsonProperty("embedding_status") String embeddingStatus,
        Map<String, Object> metadata) {

    private static final int ENABLED = 1;

    /**
     * Maps an entity onto its view.
     *
     * @param entity chunk entity
     * @return view without any two level annotation
     */
    public static ChunkResponse from(Chunk entity) {
        return of(entity, List.of());
    }

    /**
     * Maps a workbench view onto its response.
     *
     * @param view chunk together with its disabled children
     * @return view
     */
    public static ChunkResponse from(DocumentService.ChunkView view) {
        return of(view.chunk(), view.disabledChildIds());
    }

    /**
     * Maps an entity and its disabled children onto the view.
     *
     * @param entity           chunk entity
     * @param disabledChildIds children of this chunk that are excluded from retrieval
     * @return view
     */
    private static ChunkResponse of(Chunk entity, List<String> disabledChildIds) {
        return new ChunkResponse(
                entity.getChunkId(),
                entity.getDocumentVersionId(),
                entity.getParentId(),
                entity.getSeq(),
                entity.getChunkType() == null ? null : entity.getChunkType().code(),
                entity.getContent(),
                entity.getChunkTextHash(),
                entity.getEnabled() != null && entity.getEnabled() == ENABLED,
                disabledChildIds == null ? List.of() : disabledChildIds,
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
