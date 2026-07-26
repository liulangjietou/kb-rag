package io.kbrag.domain.model;

/**
 * One directed relation between two entities of the knowledge graph, requirement section 4.9.
 *
 * <p>Both endpoints are entity names rather than identifiers, for the same reason
 * {@link GraphEntity} carries none: the name is the identity inside one knowledge base.
 *
 * @param source source entity name
 * @param type   relation label, free text
 * @param target target entity name
 *
 * @author owlzhangfq@gmail.com
 */
public record GraphRelation(String source, String type, String target) {
}
