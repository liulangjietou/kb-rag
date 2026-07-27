package io.kbrag.domain.model;

/**
 * One entity of the knowledge graph, requirement section 4.9.
 *
 * <p>Identity is the pair knowledge base and name, which is why no identifier field exists: the graph
 * merges on that pair so the same person mentioned by two documents is one node, and a surrogate key
 * would silently turn every mention into a new node.
 *
 * @param name entity name as the extraction produced it, trimmed
 * @param type free text category, for example {@code person} or {@code product}
 *
 * @author owlzhangfq@gmail.com
 */
public record GraphEntity(String name, String type) {
}
