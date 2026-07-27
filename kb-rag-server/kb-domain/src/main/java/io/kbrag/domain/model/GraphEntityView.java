package io.kbrag.domain.model;

import java.util.List;

/**
 * One row of the console entity list, requirement section 4.9.
 *
 * <p>The neighbours travel inside the row rather than behind an endpoint of their own: the console draws
 * a small force directed graph out of the listed entities, so it needs the edges of exactly the rows it
 * received, and a second round trip per row would be N+1 by construction. They are capped for the same
 * reason - one hub entity with thousands of neighbours would otherwise decide the size of the response.
 *
 * @param name       entity name
 * @param type       free text category
 * @param chunkCount chunks this entity traces back to
 * @param relations  outgoing relations, truncated to {@link #MAX_RELATIONS}
 *
 * @author owlzhangfq@gmail.com
 */
public record GraphEntityView(String name, String type, long chunkCount, List<Neighbour> relations) {

    /** Relations reported per entity row. Truncation is silent: the row is a preview, not an export. */
    public static final int MAX_RELATIONS = 20;

    /**
     * One outgoing edge of an entity row.
     *
     * @param target target entity name
     * @param type   relation label
     */
    public record Neighbour(String target, String type) {
    }
}
