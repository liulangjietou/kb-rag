package io.kbrag.domain.model;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.util.List;

/**
 * Engine side projection of a chunk.
 *
 * <p>Only the fields listed as filterable in the data model reach the search engines; every other
 * metadata attribute stays in MySQL.
 */
@Getter
@Builder
@ToString(exclude = {"content", "vector"})
public class ChunkRecord {

    /** Primary key inside the engine, equal to the chunk business id. */
    private final String chunkId;

    /** Owning knowledge base. */
    private final String kbId;

    /** Owning document. */
    private final String docId;

    /** Owning document version, mandatory filter of every retrieval call. */
    private final String documentVersionId;

    /** Parent chunk id, {@code null} when parent child splitting is off. */
    private final String parentId;

    /** Chunk type code, {@code text}, {@code image} or {@code chat_log}. */
    private final String chunkType;

    /** Retrieval switch, disabled chunks are filtered out engine side. */
    private final boolean enabled;

    /** Tag ids, reserved for the metadata filter of M2. */
    private final List<String> tagIds;

    /** Chat session id, only populated for chat log chunks. */
    private final String sessionId;

    /** Chat message sender, only populated for chat log chunks. */
    private final String sender;

    /** Chat message timestamp in epoch milliseconds. */
    private final Long msgTime;

    /** Zero based order inside the document version. */
    private final Integer chunkSeq;

    /** Chunk text, indexed for BM25. */
    private final String content;

    /** Embedding vector, {@code null} when the target index has no vector field. */
    private final float[] vector;
}
