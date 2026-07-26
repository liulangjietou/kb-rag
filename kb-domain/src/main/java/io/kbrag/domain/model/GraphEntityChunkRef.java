package io.kbrag.domain.model;

/**
 * One traceability edge of an entity, as the graph stores it.
 *
 * <p>Carries no text: the chunk content is read from MySQL, the single fact source, exactly like every
 * other retrieval path does. What the graph contributes is the pointer and the version it was written
 * under, nothing that a stale copy could make wrong.
 *
 * @param chunkId           chunk business id
 * @param documentVersionId document version the chunk belonged to when the extraction ran
 *
 * @author owlzhangfq@gmail.com
 */
public record GraphEntityChunkRef(String chunkId, String documentVersionId) {
}
