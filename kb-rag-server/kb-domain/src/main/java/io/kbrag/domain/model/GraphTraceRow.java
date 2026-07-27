package io.kbrag.domain.model;

/**
 * One path the graph route found: a matched entity, the number of hops that separate it from the entity
 * whose traceability edge reaches a chunk, and that chunk.
 *
 * <p>Deliberately one row per (chunk, matched entity) pair rather than one row per chunk. Collapsing them
 * inside the store would hide which entity produced the score and would force the store to implement the
 * relevance formula, which belongs to the domain and has to stay engine independent.
 *
 * @param chunkId    chunk the traceability edge points at
 * @param entityName entity of the full text match that started this path
 * @param matchScore full text match score of that entity, normalised to {@code [0,1]} by the store
 * @param hops       relationship hops between the matched entity and the entity the chunk traces back to,
 *                   {@code 0} when the matched entity is itself mentioned in the chunk
 *
 * @author owlzhangfq@gmail.com
 */
public record GraphTraceRow(String chunkId, String entityName, double matchScore, int hops) {
}
