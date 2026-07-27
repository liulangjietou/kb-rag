package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.domain.model.GraphEntityView;

import java.util.List;

/**
 * One row of the console entity list, requirement section 4.9.
 *
 * <p>The neighbours are inlined instead of sitting behind an endpoint of their own: the console draws its
 * small graph out of exactly the rows it received, so a separate call per row would be N+1 by
 * construction. They are capped at {@link GraphEntityView#MAX_RELATIONS} and the truncation is silent -
 * the row is a preview of a hub, not an export of it.
 *
 * @param name             entity name
 * @param type             free text category
 * @param sourceChunkCount chunks this entity traces back to
 * @param relations        outgoing relations, truncated
 *
 * @author owlzhangfq@gmail.com
 */
public record GraphEntityResponse(
        String name,
        String type,
        @JsonProperty("source_chunk_count") long sourceChunkCount,
        List<Relation> relations) {

    /**
     * One outgoing edge of an entity row.
     *
     * @param target target entity name
     * @param type   relation label
     */
    public record Relation(String target, String type) {
    }

    /**
     * Maps a domain view onto the transport shape.
     *
     * @param view domain view
     * @return transport row
     */
    public static GraphEntityResponse from(GraphEntityView view) {
        return new GraphEntityResponse(view.name(), view.type(), view.chunkCount(),
                view.relations() == null ? List.of()
                        : view.relations().stream()
                                .map(edge -> new Relation(edge.target(), edge.type()))
                                .toList());
    }
}
