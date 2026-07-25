package io.kbrag.app.retrieval;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.util.List;
import java.util.Map;

/**
 * One retrieval result, engine and transport neutral.
 *
 * <p>Mirrors the shared {@code RetrievalNode} schema of the open API so the search endpoint and the
 * future chat endpoint cannot drift apart.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Builder
@ToString(exclude = "content")
public class RetrievalNodeView {

    /** Owning document. */
    private final String docId;

    /** Owning document version, proof that version isolation was applied. */
    private final String documentVersionId;

    /** Chunk business id. */
    private final String chunkId;

    /** Chunk type code. */
    private final String chunkType;

    /** Chunk text taken from the MySQL fact source, not from the search engine. */
    private final String content;

    /** Score of the route that ranked this node best. */
    private final double score;

    /** Nature of {@link #score}. */
    private final String scoreType;

    /** Route that ranked this node best. */
    private final String retrievalSource;

    /** Per route scores and ranks plus the fusion score, for the debug page. */
    private final Map<String, Object> metadata;

    /** Pre signed URLs of the images this chunk derives from, empty in M1. */
    private final List<String> imageUrls;

    /** Pre signed URL of the page snapshot, {@code null} in M1. */
    private final String previewUrl;
}
