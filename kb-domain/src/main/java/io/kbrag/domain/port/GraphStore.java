package io.kbrag.domain.port;

import io.kbrag.domain.model.GraphEntityChunkRef;
import io.kbrag.domain.model.GraphEntityView;
import io.kbrag.domain.model.GraphExtraction;
import io.kbrag.domain.model.GraphSummary;
import io.kbrag.domain.model.GraphTraceRow;
import io.kbrag.domain.model.GraphTraversalQuery;
import io.kbrag.domain.model.HealthStatus;

import java.util.List;

/**
 * Outbound port of the knowledge graph, requirement section 4.9.
 *
 * <p><b>A derived store, exactly like the vector and full text ones.</b> Nothing here is a fact source:
 * every node can be rebuilt from the MySQL chunk rows, which is why no MySQL table mirrors the graph and
 * why the retrieval path re-checks in MySQL whatever the graph hands it. The consequence is deliberate -
 * a graph that lags behind, or is missing altogether, can never change what a caller is allowed to see.
 *
 * <p><b>{@link #isEnabled()} is the one switch of the whole capability.</b> A deployment without a graph
 * gets an implementation that answers {@code false} and does nothing, so no caller has to inspect the
 * configuration and no other feature of the service notices the difference.
 *
 * <p><b>Match scores are normalised by the implementation</b>, the same contract {@code VectorStore}
 * carries: a full text engine score has no upper bound and would make the relevance formula depend on the
 * engine's scoring internals, so every implementation maps its scores onto {@code [0,1]} with the best
 * match of the result set at {@code 1}. The domain then multiplies by the hop reciprocal and nothing else.
 *
 * @author owlzhangfq@gmail.com
 */
public interface GraphStore {

    /**
     * Tells whether the deployment runs a graph at all.
     *
     * @return {@code true} when graph calls can be issued
     */
    boolean isEnabled();

    /**
     * Creates the entity name full text index, idempotently.
     *
     * <p>Owned by the extraction pipeline rather than by a startup hook: a deployment that never enables
     * the graph must not pay for a schema it will not use, and the pipeline is the first writer anyway.
     */
    void ensureSchema();

    /**
     * Writes the entities, relations and traceability edges one chunk produced.
     *
     * <p>Entities merge on the pair knowledge base and name, so two chunks naming the same entity end up
     * on one node with two traceability edges - which is the whole point of a graph over a chunk index.
     *
     * @param extraction extraction result of one chunk
     */
    void upsert(GraphExtraction extraction);

    /**
     * Matches the query terms against the entity names, expands the matches along relations and returns
     * the chunks the reached entities trace back to.
     *
     * @param query traversal request
     * @return one row per (chunk, matched entity) pair, empty when nothing matched
     */
    List<GraphTraceRow> traverse(GraphTraversalQuery query);

    /**
     * Removes the traceability edges of the given chunks and the entities that are left isolated.
     *
     * @param kbId     knowledge base business id
     * @param chunkIds chunk ids to forget, ignored when empty
     */
    void deleteChunks(String kbId, List<String> chunkIds);

    /**
     * Removes everything the given document versions traced back to, plus the entities left isolated.
     *
     * @param kbId               knowledge base business id
     * @param documentVersionIds document version ids to forget, ignored when empty
     */
    void deleteDocumentVersions(String kbId, List<String> documentVersionIds);

    /**
     * Removes the whole graph of a knowledge base.
     *
     * @param kbId knowledge base business id
     */
    void deleteKb(String kbId);

    /**
     * Reads the size of the graph of a knowledge base.
     *
     * @param kbId knowledge base business id
     * @return counts, {@link GraphSummary#EMPTY} when no graph is reachable
     */
    GraphSummary summary(String kbId);

    /**
     * Lists the entities of a knowledge base, most traced first.
     *
     * @param kbId    knowledge base business id
     * @param keyword case insensitive name filter, blank matches everything
     * @param offset  rows to skip
     * @param limit   rows to return
     * @return entity rows
     */
    List<GraphEntityView> listEntities(String kbId, String keyword, int offset, int limit);

    /**
     * Counts the entities a listing would match.
     *
     * @param kbId    knowledge base business id
     * @param keyword case insensitive name filter, blank matches everything
     * @return matching entity count
     */
    long countEntities(String kbId, String keyword);

    /**
     * Reads the traceability edges of one entity.
     *
     * @param kbId       knowledge base business id
     * @param entityName exact entity name
     * @param limit      rows to return
     * @return chunk references
     */
    List<GraphEntityChunkRef> chunksOf(String kbId, String entityName, int limit);

    /**
     * Probes graph connectivity.
     *
     * @return probe outcome
     */
    HealthStatus healthCheck();
}
