package io.kbrag.infrastructure.search.es;

import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.model.MemoryDoc;
import io.kbrag.domain.model.MemoryHit;
import io.kbrag.domain.model.MemorySearchQuery;
import io.kbrag.domain.port.MemoryStore;
import io.kbrag.domain.service.VectorScoreNormalizer;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Elasticsearch backed memory index, the M19 contract.
 *
 * <p><b>One physical index for every library.</b> A memory node is one short sentence, so even
 * many libraries stay far below the size where per library indices would pay off, and the library
 * filter is already mandatory on every query. What must never vary per library is the embedding
 * dimension, which is a deployment level property here exactly as it is for the chunk indices.
 *
 * <p><b>The vector field is added lazily.</b> The index is created without it, and the mapping is
 * extended the first time a document actually carries an embedding, taking the dimension from that
 * embedding. This keeps the BM25-only deployment working with no vector configuration at all, and
 * turns "the embedding model changed dimension" into an explicit mapping error instead of silent
 * zero recall.
 *
 * <p>Scores are normalised to {@code [0,1]} before leaving this class: vector scores through the
 * shared cosine mapping, BM25 scores through {@code s/(s+1)} - monotonic, so ordering is preserved
 * and the caller's similarity threshold stays meaningful in the degraded mode too.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EsMemoryStore implements MemoryStore {

    /** Physical index every memory node lives in. */
    private static final String INDEX_NAME = "kb_memory_nodes_v1";

    private static final String FIELD_NODE_ID = "node_id";
    private static final String FIELD_LIBRARY_ID = "library_id";
    private static final String FIELD_RULE_ID = "rule_id";
    private static final String FIELD_USER_ID = "user_id";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_EMBEDDING = "embedding";
    private static final String FIELD_EXPIRE_AT = "expire_at";

    private static final String SIMILARITY_COSINE = "cosine";
    private static final String ERROR_ALREADY_EXISTS = "resource_already_exists_exception";

    /** Multiplier applied to {@code topK} to size the kNN candidate pool, same as the chunk route. */
    private static final int CANDIDATE_FACTOR = 4;

    private final EsIndexAdmin indexAdmin;
    private final KbProperties properties;

    /** Flipped once the index is known to exist, so the existence check is not one call per write. */
    private volatile boolean indexEnsured;

    /** Flipped once the dense vector mapping is known to be present. */
    private volatile boolean vectorMappingEnsured;

    @Override
    public void upsert(MemoryDoc doc) {
        ensureIndex();
        if (doc.getEmbedding() != null) {
            ensureVectorMapping(doc.getEmbedding().length);
        }
        try {
            indexAdmin.client().index(request -> request
                    .index(INDEX_NAME)
                    .id(doc.getNodeId())
                    .document(toDocument(doc))
                    .refresh(Refresh.True));
        } catch (Exception e) {
            log.error("memory index upsert failed, errorCode={}, nodeId={}",
                    ErrorCode.INTERNAL_ERROR, doc.getNodeId(), e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "memory index upsert failed", e);
        }
    }

    @Override
    public void delete(String nodeId) {
        ensureIndex();
        try {
            indexAdmin.client().delete(request -> request
                    .index(INDEX_NAME)
                    .id(nodeId)
                    .refresh(Refresh.True));
        } catch (Exception e) {
            log.error("memory index delete failed, errorCode={}, nodeId={}",
                    ErrorCode.INTERNAL_ERROR, nodeId, e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "memory index delete failed", e);
        }
    }

    @Override
    public void deleteByLibrary(String libraryId) {
        deleteByQuery(List.of(term(FIELD_LIBRARY_ID, libraryId)), libraryId);
    }

    @Override
    public void deleteByRule(String libraryId, String ruleId) {
        deleteByQuery(List.of(term(FIELD_LIBRARY_ID, libraryId), term(FIELD_RULE_ID, ruleId)), libraryId);
    }

    @Override
    public List<MemoryHit> search(MemorySearchQuery query) {
        ensureIndex();
        List<Query> filters = toFilters(query);
        try {
            SearchResponse<MemorySource> response;
            if (query.getEmbedding() != null && vectorMappingPresent()) {
                response = knnSearch(query, filters);
                return toHits(response, true);
            }
            response = bm25Search(query, filters);
            return toHits(response, false);
        } catch (Exception e) {
            log.error("memory search failed, errorCode={}, libraryId={}",
                    ErrorCode.INTERNAL_ERROR, query.getLibraryId(), e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "memory search failed", e);
        }
    }

    private SearchResponse<MemorySource> knnSearch(MemorySearchQuery query, List<Query> filters)
            throws Exception {
        List<Float> vector = toFloatList(query.getEmbedding());
        long topK = query.getTopK();
        long candidates = topK * CANDIDATE_FACTOR;
        return indexAdmin.client().search(request -> request
                .index(INDEX_NAME)
                .size(query.getTopK())
                .source(source -> source.filter(f -> f.includes(FIELD_CONTENT)))
                .knn(knn -> knn
                        .field(FIELD_EMBEDDING)
                        .queryVector(vector)
                        .k(topK)
                        .numCandidates(candidates)
                        .filter(filters)), MemorySource.class);
    }

    private SearchResponse<MemorySource> bm25Search(MemorySearchQuery query, List<Query> filters)
            throws Exception {
        return indexAdmin.client().search(request -> request
                .index(INDEX_NAME)
                .size(query.getTopK())
                .source(source -> source.filter(f -> f.includes(FIELD_CONTENT)))
                .query(q -> q.bool(bool -> bool
                        .must(must -> must.match(match -> match
                                .field(FIELD_CONTENT)
                                .query(query.getQueryText())))
                        .filter(filters))), MemorySource.class);
    }

    private List<MemoryHit> toHits(SearchResponse<MemorySource> response, boolean vectorRoute) {
        List<MemoryHit> hits = new ArrayList<>();
        for (Hit<MemorySource> hit : response.hits().hits()) {
            double raw = hit.score() == null ? 0.0d : hit.score();
            double score = vectorRoute
                    ? VectorScoreNormalizer.fromElasticsearchScore(raw)
                    : raw / (raw + 1.0d);
            String content = hit.source() == null ? "" : hit.source().getContent();
            hits.add(MemoryHit.builder()
                    .nodeId(hit.id())
                    .content(content)
                    .score(score)
                    .build());
        }
        return hits;
    }

    /**
     * Mandatory isolation predicates plus the expiry cut, all AND-combined.
     */
    private List<Query> toFilters(MemorySearchQuery query) {
        List<Query> filters = new ArrayList<>();
        filters.add(term(FIELD_LIBRARY_ID, query.getLibraryId()));
        filters.add(term(FIELD_USER_ID, query.getUserId()));
        if (query.getRuleId() != null && !query.getRuleId().isBlank()) {
            filters.add(term(FIELD_RULE_ID, query.getRuleId()));
        }
        // A node is live when it has no expiry at all or its expiry is still ahead; comparing at
        // query time is what makes expiry work without any sweeper job.
        long now = System.currentTimeMillis();
        filters.add(Query.of(q -> q.bool(bool -> bool
                .should(s -> s.bool(inner -> inner.mustNot(mn -> mn.exists(ex -> ex.field(FIELD_EXPIRE_AT)))))
                .should(s -> s.range(r -> r.field(FIELD_EXPIRE_AT).gt(JsonData.of(now))))
                .minimumShouldMatch("1"))));
        return filters;
    }

    private Query term(String field, String value) {
        return Query.of(q -> q.term(t -> t.field(field).value(value)));
    }

    private void deleteByQuery(List<Query> filters, String libraryId) {
        ensureIndex();
        try {
            indexAdmin.client().deleteByQuery(request -> request
                    .index(INDEX_NAME)
                    .refresh(true)
                    .query(q -> q.bool(bool -> bool.filter(filters))));
            log.info("memory index bulk removal done, libraryId={}", libraryId);
        } catch (Exception e) {
            log.error("memory index bulk removal failed, errorCode={}, libraryId={}",
                    ErrorCode.INTERNAL_ERROR, libraryId, e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "memory index bulk removal failed", e);
        }
    }

    private Map<String, Object> toDocument(MemoryDoc doc) {
        Map<String, Object> document = new HashMap<>();
        document.put(FIELD_NODE_ID, doc.getNodeId());
        document.put(FIELD_LIBRARY_ID, doc.getLibraryId());
        document.put(FIELD_RULE_ID, doc.getRuleId());
        document.put(FIELD_USER_ID, doc.getUserId());
        document.put(FIELD_CONTENT, doc.getContent());
        if (doc.getExpireAt() != null) {
            document.put(FIELD_EXPIRE_AT,
                    doc.getExpireAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        }
        if (doc.getEmbedding() != null) {
            document.put(FIELD_EMBEDDING, toFloatList(doc.getEmbedding()));
        }
        return document;
    }

    private void ensureIndex() {
        if (indexEnsured) {
            return;
        }
        synchronized (this) {
            if (indexEnsured) {
                return;
            }
            try {
                boolean exists = indexAdmin.client().indices()
                        .exists(request -> request.index(INDEX_NAME)).value();
                if (!exists) {
                    createIndex();
                }
                indexEnsured = true;
            } catch (Exception e) {
                log.error("ensure memory index failed, errorCode={}, index={}",
                        ErrorCode.INTERNAL_ERROR, INDEX_NAME, e);
                throw new BizException(ErrorCode.INTERNAL_ERROR, "ensure memory index failed", e);
            }
        }
    }

    /**
     * Creates the index without the vector field, falling back to the standard analyzer when the
     * configured one is not installed - the same tolerance the chunk indices apply.
     */
    private void createIndex() throws Exception {
        String configured = properties.getEs().getContentAnalyzer();
        try {
            doCreate(configured);
        } catch (Exception first) {
            if (isAlreadyExists(first)) {
                return;
            }
            String fallback = properties.getEs().getFallbackAnalyzer();
            log.info("content analyzer unavailable for memory index, falling back, configured={}, fallback={}",
                    configured, fallback);
            doCreate(fallback);
        }
    }

    private void doCreate(String analyzer) throws Exception {
        Map<String, Property> mapping = new LinkedHashMap<>();
        mapping.put(FIELD_NODE_ID, keyword());
        mapping.put(FIELD_LIBRARY_ID, keyword());
        mapping.put(FIELD_RULE_ID, keyword());
        mapping.put(FIELD_USER_ID, keyword());
        mapping.put(FIELD_EXPIRE_AT, Property.of(p -> p.long_(l -> l)));
        mapping.put(FIELD_CONTENT, Property.of(p -> p.text(t -> t.analyzer(analyzer))));
        String shards = String.valueOf(properties.getEs().getNumberOfShards());
        String replicas = String.valueOf(properties.getEs().getNumberOfReplicas());
        indexAdmin.client().indices().create(request -> request
                .index(INDEX_NAME)
                .settings(settings -> settings
                        .numberOfShards(shards)
                        .numberOfReplicas(replicas))
                .mappings(mappings -> mappings.properties(mapping)));
        log.info("memory index created, index={}, analyzer={}", INDEX_NAME, analyzer);
    }

    private Property keyword() {
        return Property.of(p -> p.keyword(k -> k));
    }

    /**
     * Adds the dense vector field the first time an embedding arrives, using its dimension.
     *
     * <p>Re-declaring an identical field is a no-op for Elasticsearch; a dimension mismatch fails
     * loudly, which is correct - a changed embedding model requires the index to be rebuilt, not a
     * silent mixture of incomparable vectors.
     */
    private void ensureVectorMapping(int dimension) {
        if (vectorMappingEnsured) {
            return;
        }
        synchronized (this) {
            if (vectorMappingEnsured) {
                return;
            }
            try {
                indexAdmin.client().indices().putMapping(request -> request
                        .index(INDEX_NAME)
                        .properties(FIELD_EMBEDDING, Property.of(p -> p.denseVector(v -> v
                                .dims(dimension)
                                .index(true)
                                .similarity(SIMILARITY_COSINE)))));
                vectorMappingEnsured = true;
                log.info("memory index vector mapping ensured, dimension={}", dimension);
            } catch (Exception e) {
                log.error("ensure memory vector mapping failed, errorCode={}, dimension={}",
                        ErrorCode.INTERNAL_ERROR, dimension, e);
                throw new BizException(ErrorCode.INTERNAL_ERROR, "ensure memory vector mapping failed", e);
            }
        }
    }

    /**
     * Tells whether the vector field exists, consulting the cluster once and caching a positive
     * answer. Needed on the search path because the flag starts false in a process that never
     * wrote an embedding, while the index may already hold vectors written by an earlier run.
     */
    private boolean vectorMappingPresent() {
        if (vectorMappingEnsured) {
            return true;
        }
        try {
            boolean present = indexAdmin.client().indices()
                    .getMapping(request -> request.index(INDEX_NAME))
                    .result().values().stream()
                    .anyMatch(record -> record.mappings().properties().containsKey(FIELD_EMBEDDING));
            if (present) {
                vectorMappingEnsured = true;
            }
            return present;
        } catch (Exception e) {
            log.error("memory vector mapping check failed, errorCode={}", ErrorCode.INTERNAL_ERROR, e);
            return false;
        }
    }

    private List<Float> toFloatList(float[] vector) {
        List<Float> values = new ArrayList<>(vector.length);
        for (float value : vector) {
            values.add(value);
        }
        return values;
    }

    private boolean isAlreadyExists(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains(ERROR_ALREADY_EXISTS)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * Source projection of a memory document: only the content is read back, the filters and the
     * vector never leave the engine.
     */
    @Getter
    @Setter
    public static class MemorySource {

        /** Remembered content as stored in the index. */
        @JsonProperty(FIELD_CONTENT)
        private String content;
    }
}
