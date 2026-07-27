package io.kbrag.domain.model;

import java.util.List;

/**
 * Relevance of one chunk to a query along the graph, requirement section 4.9 "path hop reciprocal times
 * entity match score".
 *
 * @param chunkId      chunk business id
 * @param score        relevance the in base ranking of the graph route is built on
 * @param hops         hop count of the path that produced {@link #score}
 * @param entityNames  matched entity names that reached this chunk, best path first
 *
 * @author owlzhangfq@gmail.com
 */
public record GraphChunkRelevance(String chunkId, double score, int hops, List<String> entityNames) {
}
