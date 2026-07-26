package io.kbrag.domain.model;

/**
 * Size of the graph of one knowledge base, requirement section 4.9 console tab.
 *
 * @param entityCount       distinct entities of the knowledge base
 * @param relationCount     relations between them
 * @param coveredChunkCount chunks at least one entity traces back to
 *
 * @author owlzhangfq@gmail.com
 */
public record GraphSummary(long entityCount, long relationCount, long coveredChunkCount) {

    /** Size reported when no graph is reachable, so the console renders zeros rather than an error. */
    public static final GraphSummary EMPTY = new GraphSummary(0L, 0L, 0L);
}
