package io.kbrag.domain.model;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.util.List;

/**
 * One graph route request, requirement section 4.9.
 *
 * <p><b>No model call is involved.</b> The query arrives already tokenised, so the route costs one graph
 * round trip and nothing else; asking a model to extract entities out of the query would put a second
 * generation call on the critical path of every search.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Builder
@ToString
public class GraphTraversalQuery {

    /** Knowledge base the traversal is confined to. */
    private final String kbId;

    /** Query terms matched against the entity name full text index. */
    private final List<String> terms;

    /** Entities of the full text match kept, highest scoring first. */
    private final int entityMatchLimit;

    /** Relationship hops the matched entities are expanded by. */
    private final int maxHops;

    /** Chunks the traversal returns at most. */
    private final int chunkLimit;
}
