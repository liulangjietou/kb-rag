package io.kbrag.domain.model;

import java.util.List;

/**
 * Everything one chunk contributed to the knowledge graph, requirement section 4.9.
 *
 * <p>The unit is a chunk rather than a document because the graph route recalls chunks: the traceability
 * edge written next to the entities is what lets a matched entity name a passage a caller can read, and
 * the document version travels with it so a version switch can invalidate exactly what it superseded.
 *
 * @param kbId              knowledge base business id
 * @param chunkId           chunk the entities were extracted from
 * @param documentVersionId document version the chunk belongs to
 * @param entities          entities found in the chunk
 * @param relations         relations whose two endpoints are both in {@link #entities}
 *
 * @author owlzhangfq@gmail.com
 */
public record GraphExtraction(String kbId, String chunkId, String documentVersionId,
                              List<GraphEntity> entities, List<GraphRelation> relations) {
}
