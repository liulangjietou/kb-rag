package io.kbrag.infrastructure.graph;

import io.kbrag.domain.model.GraphEntityChunkRef;
import io.kbrag.domain.model.GraphEntityView;
import io.kbrag.domain.model.GraphExtraction;
import io.kbrag.domain.model.GraphSummary;
import io.kbrag.domain.model.GraphTraceRow;
import io.kbrag.domain.model.GraphTraversalQuery;
import io.kbrag.domain.model.HealthStatus;
import io.kbrag.domain.port.GraphStore;

import java.util.List;

/**
 * Graph store of a deployment that runs no graph, requirement section 4.9 "a blank {@code NEO4J_URI}
 * disables the graph capability".
 *
 * <p>The same device the zero key model providers use: rather than letting every caller ask whether a
 * graph exists, one implementation answers "no" to {@link #isEnabled()} and does nothing everywhere
 * else. The cascade paths can therefore call the store unconditionally, which is what keeps the removal
 * of a document identical in a deployment with and without Neo4j.
 *
 * <p>The read operations return empty rather than failing on purpose: the console's graph tab is
 * reachable in any deployment and has to render an empty graph, not an error page.
 *
 * @author owlzhangfq@gmail.com
 */
public class DisabledGraphStore implements GraphStore {

    private static final String DETAIL = "neo4j not configured";

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public void ensureSchema() {
        // Nothing to create: there is no graph.
    }

    @Override
    public void upsert(GraphExtraction extraction) {
        // Nothing to write: the extraction task refuses to start without a graph.
    }

    @Override
    public List<GraphTraceRow> traverse(GraphTraversalQuery query) {
        return List.of();
    }

    @Override
    public void deleteChunks(String kbId, List<String> chunkIds) {
        // Nothing to forget.
    }

    @Override
    public void deleteDocumentVersions(String kbId, List<String> documentVersionIds) {
        // Nothing to forget.
    }

    @Override
    public void deleteKb(String kbId) {
        // Nothing to forget.
    }

    @Override
    public GraphSummary summary(String kbId) {
        return GraphSummary.EMPTY;
    }

    @Override
    public List<GraphEntityView> listEntities(String kbId, String keyword, int offset, int limit) {
        return List.of();
    }

    @Override
    public long countEntities(String kbId, String keyword) {
        return 0L;
    }

    @Override
    public List<GraphEntityChunkRef> chunksOf(String kbId, String entityName, int limit) {
        return List.of();
    }

    @Override
    public HealthStatus healthCheck() {
        return HealthStatus.up(DETAIL);
    }
}
