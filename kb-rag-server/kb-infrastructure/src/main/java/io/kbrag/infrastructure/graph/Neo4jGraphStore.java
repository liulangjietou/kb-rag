package io.kbrag.infrastructure.graph;

import io.kbrag.common.api.ErrorCode;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.model.GraphEntity;
import io.kbrag.domain.model.GraphEntityChunkRef;
import io.kbrag.domain.model.GraphEntityView;
import io.kbrag.domain.model.GraphExtraction;
import io.kbrag.domain.model.GraphRelation;
import io.kbrag.domain.model.GraphSummary;
import io.kbrag.domain.model.GraphTraceRow;
import io.kbrag.domain.model.GraphTraversalQuery;
import io.kbrag.domain.model.HealthStatus;
import io.kbrag.domain.port.GraphStore;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Neo4j implementation of the knowledge graph port, over Bolt, requirement section 4.9.
 *
 * <p><b>Graph shape.</b> {@code (:Entity {kb_id, name, type})-[:REL {type}]->(:Entity)} carries the
 * knowledge, {@code (:Entity)-[:MENTIONED_IN]->(:Chunk {chunk_id, document_version_id, kb_id})} carries
 * the traceability. Entities merge on the pair {@code (kb_id, name)}, so the same name extracted from a
 * hundred chunks is one node with a hundred traceability edges - which is the only reason a multi hop
 * question can reach a passage that never contained the words of the question.
 *
 * <p><b>Every knowledge base lives in the same database, separated by {@code kb_id}.</b> A database per
 * base would put an administrative operation on the knowledge base creation path and is an Enterprise
 * feature besides; the property is on every node and on every predicate here instead, which is also what
 * makes the deletion of one base a single predicate rather than a schema operation.
 *
 * <p><b>Schema uses plain composite indexes, not uniqueness constraints.</b> A constraint would make the
 * merges race free, but composite constraint support differs between editions and a schema statement
 * that fails on a community server would leave the whole capability unusable. What keeps an indexed
 * {@code MERGE} safe instead is the caller: {@code GraphExtractionService} funnels the writes of one
 * knowledge base through a single writer thread, so no two transactions merge the same
 * {@code (kb_id, name)} pair concurrently. Two knowledge bases writing at once never collide - the pair
 * differs by {@code kb_id} - which is why that serialisation is per run rather than global.
 *
 * <p><b>Match scores are normalised against the best match of the same result set</b>, as the port
 * requires: a Lucene score has no upper bound, and the relevance formula the debug page displays has to
 * mean the same thing on every corpus.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
public class Neo4jGraphStore implements GraphStore {

    /** Full text index of the entity names, the entry point of every graph route call. */
    static final String ENTITY_NAME_INDEX = "kb_entity_name_fulltext";

    /**
     * Analyser of the entity name index. The CJK analyser bigrams ideographs and lower cases Latin words,
     * which is the recall a deployment without a Chinese dictionary can get; the tokenizer on the query
     * side is deliberately built to match it.
     */
    private static final String ENTITY_NAME_ANALYZER = "cjk";

    private static final String CYPHER_ENTITY_INDEX =
            "CREATE INDEX kb_entity_lookup IF NOT EXISTS FOR (e:Entity) ON (e.kb_id, e.name)";
    private static final String CYPHER_CHUNK_INDEX =
            "CREATE INDEX kb_chunk_lookup IF NOT EXISTS FOR (c:Chunk) ON (c.kb_id, c.chunk_id)";
    private static final String CYPHER_VERSION_INDEX =
            "CREATE INDEX kb_chunk_version_lookup IF NOT EXISTS "
                    + "FOR (c:Chunk) ON (c.kb_id, c.document_version_id)";
    private static final String CYPHER_FULLTEXT_INDEX =
            "CREATE FULLTEXT INDEX " + ENTITY_NAME_INDEX + " IF NOT EXISTS FOR (e:Entity) ON EACH [e.name] "
                    + "OPTIONS {indexConfig: {`fulltext.analyzer`: '" + ENTITY_NAME_ANALYZER + "'}}";

    private static final String CYPHER_UPSERT_ENTITIES = """
            MERGE (c:Chunk {chunk_id: $chunkId})
              SET c.kb_id = $kbId, c.document_version_id = $documentVersionId
            WITH c
            UNWIND $entities AS entity
            MERGE (e:Entity {kb_id: $kbId, name: entity.name})
              ON CREATE SET e.type = entity.type
            MERGE (e)-[:MENTIONED_IN]->(c)""";

    private static final String CYPHER_UPSERT_RELATIONS = """
            UNWIND $relations AS relation
            MATCH (source:Entity {kb_id: $kbId, name: relation.source})
            MATCH (target:Entity {kb_id: $kbId, name: relation.target})
            MERGE (source)-[:REL {type: relation.type}]->(target)""";

    /**
     * Graph route query. The variable length bound is spliced in as a literal because Cypher does not
     * accept a parameter there; the value is an integer clamped by {@link #boundedHops(int)} before it
     * ever reaches the string, so no caller supplied text takes part in the statement.
     */
    private static final String CYPHER_TRAVERSE_TEMPLATE = """
            CALL db.index.fulltext.queryNodes($indexName, $luceneQuery) YIELD node, score
            WHERE node.kb_id = $kbId
            WITH node, score ORDER BY score DESC LIMIT $entityMatchLimit
            WITH collect({entity: node, score: score}) AS matches, max(score) AS topScore
            UNWIND matches AS match
            WITH match.entity AS seed, match.score / topScore AS matchScore
            MATCH path = (seed)-[:REL*0..%d]-(reached:Entity)
            WITH seed, matchScore, reached, min(length(path)) AS hops
            MATCH (reached)-[:MENTIONED_IN]->(chunk:Chunk {kb_id: $kbId})
            RETURN chunk.chunk_id AS chunkId, seed.name AS entityName,
                   matchScore AS matchScore, hops AS hops
            ORDER BY matchScore DESC, hops ASC
            LIMIT $rowLimit""";

    private static final String CYPHER_DELETE_CHUNKS =
            "MATCH (c:Chunk {kb_id: $kbId}) WHERE c.chunk_id IN $chunkIds DETACH DELETE c";
    private static final String CYPHER_DELETE_VERSIONS =
            "MATCH (c:Chunk {kb_id: $kbId}) WHERE c.document_version_id IN $documentVersionIds "
                    + "DETACH DELETE c";
    private static final String CYPHER_DELETE_ISOLATED_ENTITIES =
            "MATCH (e:Entity {kb_id: $kbId}) WHERE NOT (e)-[:MENTIONED_IN]->(:Chunk) DETACH DELETE e";
    private static final String CYPHER_DELETE_KB =
            "MATCH (n) WHERE n.kb_id = $kbId WITH n LIMIT $batchSize DETACH DELETE n RETURN count(n) AS removed";

    private static final String CYPHER_SUMMARY = """
            CALL { MATCH (e:Entity {kb_id: $kbId}) RETURN count(e) AS entityCount }
            CALL { MATCH (:Entity {kb_id: $kbId})-[r:REL]->(:Entity) RETURN count(r) AS relationCount }
            CALL { MATCH (c:Chunk {kb_id: $kbId}) RETURN count(c) AS chunkCount }
            RETURN entityCount, relationCount, chunkCount""";

    private static final String CYPHER_LIST_ENTITIES = """
            MATCH (e:Entity {kb_id: $kbId})
            WHERE $keyword = '' OR toLower(e.name) CONTAINS $keyword
            OPTIONAL MATCH (e)-[:MENTIONED_IN]->(c:Chunk)
            WITH e, count(c) AS chunkCount
            ORDER BY chunkCount DESC, e.name ASC
            SKIP $offset LIMIT $limit
            CALL {
              WITH e
              MATCH (e)-[r:REL]->(n:Entity)
              RETURN collect({target: n.name, type: r.type})[..$relationLimit] AS relations
            }
            RETURN e.name AS name, e.type AS type, chunkCount AS chunkCount, relations AS relations""";

    private static final String CYPHER_COUNT_ENTITIES = """
            MATCH (e:Entity {kb_id: $kbId})
            WHERE $keyword = '' OR toLower(e.name) CONTAINS $keyword
            RETURN count(e) AS total""";

    private static final String CYPHER_ENTITY_CHUNKS = """
            MATCH (:Entity {kb_id: $kbId, name: $entityName})-[:MENTIONED_IN]->(c:Chunk)
            RETURN c.chunk_id AS chunkId, c.document_version_id AS documentVersionId
            ORDER BY chunkId
            LIMIT $limit""";

    private static final String CYPHER_PING = "RETURN 1 AS ok";

    /** Characters the Lucene query parser would read as syntax rather than as part of a name. */
    private static final String LUCENE_RESERVED = "+-&|!(){}[]^\"~*?:\\/";

    private static final String LUCENE_OR = " OR ";
    private static final char LUCENE_ESCAPE = '\\';

    /** Hops accepted for one traversal; deeper walks explode combinatorially without adding evidence. */
    private static final int MIN_HOPS = 0;
    private static final int MAX_HOPS = 5;

    /** Nodes removed per statement when a whole knowledge base is dropped. */
    private static final int DELETE_BATCH_SIZE = 1000;

    /** Chunk ids per delete statement, matching the batch size the engine cleaner already uses. */
    private static final int CHUNK_BATCH_SIZE = 500;

    /**
     * Traversal rows read per call. One chunk can be reached by several matched entities, so the row
     * budget is a multiple of the chunk budget rather than the chunk budget itself.
     */
    private static final int ROWS_PER_CHUNK = 4;

    private final Driver driver;
    private final String uri;

    public Neo4jGraphStore(Driver driver, KbProperties properties) {
        this.driver = driver;
        this.uri = properties.getGraph().getUri();
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public void ensureSchema() {
        try (Session session = driver.session()) {
            session.executeWriteWithoutResult(tx -> {
                tx.run(CYPHER_ENTITY_INDEX).consume();
                tx.run(CYPHER_CHUNK_INDEX).consume();
                tx.run(CYPHER_VERSION_INDEX).consume();
                tx.run(CYPHER_FULLTEXT_INDEX).consume();
            });
        }
        log.info("graph schema ensured, fulltextIndex={}, analyzer={}",
                ENTITY_NAME_INDEX, ENTITY_NAME_ANALYZER);
    }

    @Override
    public void upsert(GraphExtraction extraction) {
        if (extraction == null || CollectionUtils.isEmpty(extraction.entities())) {
            return;
        }
        List<Map<String, Object>> entities = new ArrayList<>(extraction.entities().size());
        for (GraphEntity entity : extraction.entities()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", entity.name());
            row.put("type", entity.type());
            entities.add(row);
        }
        List<Map<String, Object>> relations = new ArrayList<>();
        for (GraphRelation relation : extraction.relations()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("source", relation.source());
            row.put("type", relation.type());
            row.put("target", relation.target());
            relations.add(row);
        }
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("kbId", extraction.kbId());
        parameters.put("chunkId", extraction.chunkId());
        parameters.put("documentVersionId", extraction.documentVersionId());
        parameters.put("entities", entities);
        parameters.put("relations", relations);
        try (Session session = driver.session()) {
            // One transaction for both statements: a relation whose endpoints were merged by a
            // transaction that then rolled back would point at nodes nobody can explain.
            session.executeWriteWithoutResult(tx -> {
                tx.run(CYPHER_UPSERT_ENTITIES, parameters).consume();
                if (!relations.isEmpty()) {
                    tx.run(CYPHER_UPSERT_RELATIONS, parameters).consume();
                }
            });
        }
    }

    @Override
    public List<GraphTraceRow> traverse(GraphTraversalQuery query) {
        String luceneQuery = luceneQueryOf(query.getTerms());
        if (luceneQuery.isEmpty()) {
            return List.of();
        }
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("indexName", ENTITY_NAME_INDEX);
        parameters.put("luceneQuery", luceneQuery);
        parameters.put("kbId", query.getKbId());
        parameters.put("entityMatchLimit", query.getEntityMatchLimit());
        parameters.put("rowLimit", query.getChunkLimit() * ROWS_PER_CHUNK);
        String cypher = String.format(CYPHER_TRAVERSE_TEMPLATE, boundedHops(query.getMaxHops()));
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                List<GraphTraceRow> rows = new ArrayList<>();
                for (Record record : tx.run(cypher, parameters).list()) {
                    rows.add(new GraphTraceRow(record.get("chunkId").asString(),
                            record.get("entityName").asString(),
                            record.get("matchScore").asDouble(),
                            record.get("hops").asInt()));
                }
                return rows;
            });
        }
    }

    @Override
    public void deleteChunks(String kbId, List<String> chunkIds) {
        if (CollectionUtils.isEmpty(chunkIds)) {
            return;
        }
        try (Session session = driver.session()) {
            for (int start = 0; start < chunkIds.size(); start += CHUNK_BATCH_SIZE) {
                List<String> batch = chunkIds.subList(start,
                        Math.min(chunkIds.size(), start + CHUNK_BATCH_SIZE));
                session.executeWriteWithoutResult(tx -> tx.run(CYPHER_DELETE_CHUNKS,
                        Values.parameters("kbId", kbId, "chunkIds", batch)).consume());
            }
            removeIsolatedEntities(session, kbId);
        }
        log.info("graph chunks removed, kbId={}, count={}", kbId, chunkIds.size());
    }

    @Override
    public void deleteDocumentVersions(String kbId, List<String> documentVersionIds) {
        if (CollectionUtils.isEmpty(documentVersionIds)) {
            return;
        }
        try (Session session = driver.session()) {
            session.executeWriteWithoutResult(tx -> tx.run(CYPHER_DELETE_VERSIONS,
                    Values.parameters("kbId", kbId, "documentVersionIds", documentVersionIds)).consume());
            removeIsolatedEntities(session, kbId);
        }
        log.info("graph document versions removed, kbId={}, versions={}",
                kbId, documentVersionIds.size());
    }

    @Override
    public void deleteKb(String kbId) {
        long removed;
        long total = 0L;
        try (Session session = driver.session()) {
            do {
                // Batched rather than one statement: a large knowledge base would otherwise build a
                // transaction big enough to exhaust the server's heap, and the deletion is idempotent.
                removed = session.executeWrite(tx -> tx.run(CYPHER_DELETE_KB,
                                Values.parameters("kbId", kbId, "batchSize", DELETE_BATCH_SIZE))
                        .single().get("removed").asLong());
                total += removed;
            } while (removed > 0);
        }
        log.info("graph knowledge base removed, kbId={}, nodes={}", kbId, total);
    }

    @Override
    public GraphSummary summary(String kbId) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                Record record = tx.run(CYPHER_SUMMARY, Values.parameters("kbId", kbId)).single();
                return new GraphSummary(record.get("entityCount").asLong(),
                        record.get("relationCount").asLong(),
                        record.get("chunkCount").asLong());
            });
        }
    }

    @Override
    public List<GraphEntityView> listEntities(String kbId, String keyword, int offset, int limit) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("kbId", kbId);
        parameters.put("keyword", normalizedKeyword(keyword));
        parameters.put("offset", offset);
        parameters.put("limit", limit);
        parameters.put("relationLimit", GraphEntityView.MAX_RELATIONS);
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                List<GraphEntityView> entities = new ArrayList<>();
                for (Record record : tx.run(CYPHER_LIST_ENTITIES, parameters).list()) {
                    entities.add(new GraphEntityView(record.get("name").asString(),
                            record.get("type").asString(null),
                            record.get("chunkCount").asLong(),
                            neighboursOf(record)));
                }
                return entities;
            });
        }
    }

    /**
     * Reads the neighbour list of one entity row.
     *
     * @param record entity row
     * @return outgoing edges, empty when the entity has none
     */
    private List<GraphEntityView.Neighbour> neighboursOf(Record record) {
        List<Object> raw = record.get("relations").asList();
        List<GraphEntityView.Neighbour> neighbours = new ArrayList<>(raw.size());
        for (Object item : raw) {
            if (item instanceof Map<?, ?> edge) {
                Object target = edge.get("target");
                if (target != null) {
                    neighbours.add(new GraphEntityView.Neighbour(String.valueOf(target),
                            edge.get("type") == null ? null : String.valueOf(edge.get("type"))));
                }
            }
        }
        return neighbours;
    }

    @Override
    public long countEntities(String kbId, String keyword) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> tx.run(CYPHER_COUNT_ENTITIES,
                            Values.parameters("kbId", kbId, "keyword", normalizedKeyword(keyword)))
                    .single().get("total").asLong());
        }
    }

    @Override
    public List<GraphEntityChunkRef> chunksOf(String kbId, String entityName, int limit) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                List<GraphEntityChunkRef> refs = new ArrayList<>();
                for (Record record : tx.run(CYPHER_ENTITY_CHUNKS, Values.parameters(
                        "kbId", kbId, "entityName", entityName, "limit", limit)).list()) {
                    refs.add(new GraphEntityChunkRef(record.get("chunkId").asString(),
                            record.get("documentVersionId").asString(null)));
                }
                return refs;
            });
        }
    }

    @Override
    public HealthStatus healthCheck() {
        try (Session session = driver.session()) {
            session.executeRead(tx -> tx.run(CYPHER_PING).single().get("ok").asInt());
            return HealthStatus.up(uri);
        } catch (Exception e) {
            log.error("neo4j health check failed, errorCode={}, uri={}", ErrorCode.INTERNAL_ERROR, uri, e);
            return HealthStatus.down(e.getMessage());
        }
    }

    /**
     * Drops the entities no chunk traces back to any more.
     *
     * <p>Runs after every removal rather than on a schedule: an entity whose last traceability edge is
     * gone is not stale data, it is a node that would still be matched by the full text index and would
     * expand into paths reaching nothing.
     *
     * @param session open session
     * @param kbId    knowledge base business id
     */
    private void removeIsolatedEntities(Session session, String kbId) {
        session.executeWriteWithoutResult(tx -> tx.run(CYPHER_DELETE_ISOLATED_ENTITIES,
                Values.parameters("kbId", kbId)).consume());
    }

    /**
     * Builds the full text query out of the tokenised terms.
     *
     * <p>Every term is escaped and the clauses are joined with {@code OR}: a query naming three things
     * should reach an entity naming one of them, and requiring all of them would make the graph route
     * answer only the questions the BM25 route already answers.
     *
     * @param terms tokenised query terms
     * @return Lucene query, empty when nothing is searchable
     */
    static String luceneQueryOf(List<String> terms) {
        if (CollectionUtils.isEmpty(terms)) {
            return "";
        }
        StringBuilder query = new StringBuilder();
        for (String term : terms) {
            String escaped = escapeLucene(term);
            if (escaped.isEmpty()) {
                continue;
            }
            if (query.length() > 0) {
                query.append(LUCENE_OR);
            }
            query.append(escaped);
        }
        return query.toString();
    }

    /**
     * Escapes the characters the Lucene query parser treats as syntax.
     *
     * @param term one query term
     * @return escaped term
     */
    private static String escapeLucene(String term) {
        StringBuilder escaped = new StringBuilder(term.length() + term.length() / 2);
        for (int index = 0; index < term.length(); index++) {
            char character = term.charAt(index);
            if (LUCENE_RESERVED.indexOf(character) >= 0) {
                escaped.append(LUCENE_ESCAPE);
            }
            escaped.append(character);
        }
        return escaped.toString();
    }

    /**
     * Clamps the configured hop count into the range the traversal statement accepts.
     *
     * @param maxHops configured hop count
     * @return bounded hop count
     */
    static int boundedHops(int maxHops) {
        return Math.min(MAX_HOPS, Math.max(MIN_HOPS, maxHops));
    }

    /**
     * Normalises the console's entity filter: blank matches everything, and the comparison is case
     * insensitive on both sides.
     *
     * @param keyword raw filter
     * @return lower case filter, empty when nothing was asked
     */
    private String normalizedKeyword(String keyword) {
        return keyword == null ? "" : keyword.trim().toLowerCase();
    }
}
