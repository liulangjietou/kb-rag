package io.kbrag.infrastructure.search.qdrant;

import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.constant.IndexFields;
import io.kbrag.domain.enums.RetrievalSource;
import io.kbrag.domain.enums.VectorEngine;
import io.kbrag.domain.model.ChunkRecord;
import io.kbrag.domain.model.HealthStatus;
import io.kbrag.domain.model.IndexSpec;
import io.kbrag.domain.model.MetadataFilter;
import io.kbrag.domain.model.RetrievalFilter;
import io.kbrag.domain.model.ScoredChunk;
import io.kbrag.domain.model.VectorQuery;
import io.kbrag.domain.port.VectorStore;
import io.kbrag.domain.service.VectorScoreNormalizer;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Qdrant backed vector route, used by the full deployment where Elasticsearch only serves BM25.
 *
 * <p><b>Score conversion.</b> Qdrant returns the raw cosine similarity in {@code [-1,1]} for a
 * {@code Cosine} collection, so the implementation only applies the shared linear mapping to
 * {@code [0,1]}. Combined with the Elasticsearch implementation, which first restores the raw cosine
 * from its own {@code (1+cos)/2} score, a score threshold means exactly the same thing in both
 * deployment modes.
 *
 * <p><b>Point identity.</b> Qdrant accepts only an unsigned integer or a UUID as a point id, while a
 * chunk id is a business string such as {@code ck_01fc063697894e04}. Every point is therefore keyed by
 * a UUID derived deterministically from the chunk id, and the chunk id itself is carried in the
 * payload. The derivation is a pure function, so a later write, delete or copy of the same chunk lands
 * on the same point without a lookup table.
 *
 * <p><b>Transport.</b> The REST API is used rather than the gRPC SDK: the whole surface this port
 * needs is covered by it, and the module already ships an HTTP stack, so no gRPC or protobuf tree is
 * pulled in.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "kb.vector", name = "engine", havingValue = "qdrant")
public class QdrantVectorStore implements VectorStore {

    /** Cosine distance, the metric the score contract is stated in. */
    private static final String DISTANCE_COSINE = "Cosine";

    /** HNSW build parameters. */
    private static final int HNSW_M = 16;
    private static final int HNSW_EF_CONSTRUCT = 200;

    /** Search time HNSW breadth. */
    private static final int SEARCH_HNSW_EF = 64;

    /** Points read back and written per batch of a snapshot copy. */
    private static final int COPY_BATCH_SIZE = 500;

    /** Payload content is stored for debugging only, hence the cap. */
    private static final int CONTENT_MAX_LENGTH = 65535;

    /**
     * Payload fields that carry a filter predicate and therefore get an index.
     *
     * <p>Qdrant filters without them by scanning, so a missing index costs latency rather than
     * correctness - which is why a failure to create one is logged instead of aborting provisioning.
     */
    private static final Map<String, String> INDEXED_PAYLOAD_FIELDS = Map.of(
            IndexFields.KB_ID, "keyword",
            IndexFields.DOC_ID, "keyword",
            IndexFields.DOCUMENT_VERSION_ID, "keyword",
            IndexFields.ENABLED, "bool",
            IndexFields.TAG_IDS, "keyword",
            IndexFields.SESSION_ID, "keyword",
            IndexFields.SENDER, "keyword",
            IndexFields.MSG_TIME, "integer");

    private final RestClient client;

    /** Budget of one snapshot copy, read once so the field list stays a plain value. */
    private final long snapshotTimeoutMs;

    public QdrantVectorStore(RestClient qdrantRestClient, KbProperties properties) {
        this.client = qdrantRestClient;
        this.snapshotTimeoutMs = properties.getApp().getSnapshotTimeoutMs();
    }

    @Override
    public String engine() {
        return VectorEngine.QDRANT.code();
    }

    @Override
    public void ensureIndex(IndexSpec spec) {
        if (!spec.hasVectorField()) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "qdrant collection requires a vector dimension");
        }
        String collection = spec.getPhysicalIndexName();
        provisionCollection(collection, spec.getDimension());
        bindAlias(collection, spec.getAliasName());
        log.info("qdrant collection ready, collection={}, alias={}", collection, spec.getAliasName());
    }

    /**
     * Creates the collection with its payload indexes when it is missing, without touching any alias.
     *
     * <p>Shared by the live index provisioning and by the snapshot copy: the two must produce an identical
     * schema, and the only thing that differs between them is whether an alias ends up pointing at the
     * result.
     *
     * @param collection collection name
     * @param dimension  vector dimension
     */
    private void provisionCollection(String collection, int dimension) {
        if (indexExists(collection)) {
            return;
        }
        Map<String, Object> body = Map.of(
                "vectors", Map.of("size", dimension, "distance", DISTANCE_COSINE),
                "hnsw_config", Map.of("m", HNSW_M, "ef_construct", HNSW_EF_CONSTRUCT));
        put("/collections/" + collection, body, "create collection");
        createPayloadIndexes(collection);
    }

    /**
     * Creates the payload indexes backing the filter predicates.
     *
     * @param collection collection name
     */
    private void createPayloadIndexes(String collection) {
        INDEXED_PAYLOAD_FIELDS.forEach((field, schema) -> {
            try {
                put("/collections/" + collection + "/index?wait=true",
                        Map.of("field_name", field, "field_schema", schema), "create payload index");
            } catch (Exception e) {
                log.info("skip payload index, collection={}, field={}, reason={}",
                        collection, field, e.getMessage());
            }
        });
    }

    @Override
    public void upsert(String alias, List<ChunkRecord> records) {
        if (CollectionUtils.isEmpty(records)) {
            return;
        }
        List<Map<String, Object>> points = new ArrayList<>(records.size());
        for (ChunkRecord record : records) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("id", pointId(record.getChunkId()));
            point.put("vector", toFloatList(record.getVector()));
            point.put("payload", toPayload(record));
            points.add(point);
        }
        put("/collections/" + alias + "/points?wait=true", Map.of("points", points), "upsert");
    }

    @Override
    public void delete(String alias, List<String> chunkIds) {
        if (CollectionUtils.isEmpty(chunkIds)) {
            return;
        }
        post("/collections/" + alias + "/points/delete?wait=true",
                Map.of("points", chunkIds.stream().map(this::pointId).toList()), "delete");
    }

    /**
     * {@inheritDoc}
     *
     * <p>Applied to the collection: Qdrant updates a payload field in place, so the retrieval switch is
     * flipped without reading back or recomputing the embedding. The vector is not part of the request
     * and is left untouched.
     */
    @Override
    public void updateEnabled(String alias, List<String> chunkIds, boolean enabled) {
        if (CollectionUtils.isEmpty(chunkIds)) {
            return;
        }
        post("/collections/" + alias + "/points/payload?wait=true",
                Map.of("payload", Map.of(IndexFields.ENABLED, enabled),
                        "points", chunkIds.stream().map(this::pointId).toList()),
                "update enabled");
        log.info("qdrant enabled flag updated, collection={}, enabled={}, chunks={}",
                alias, enabled, chunkIds.size());
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>A copy, not a hard link.</b> Qdrant's own snapshot facility produces a file to be restored
     * under a different name through a separate upload step, which is neither synchronous nor addressable
     * as a live collection. The snapshot is therefore created by reading every point of the source and
     * writing it into a fresh collection with the same schema - the storage and time cost requirement
     * section 4.7 accepts when it says the release doubles storage.
     *
     * <p><b>Why scroll and not a paged query.</b> Scrolling carries an explicit cursor
     * ({@code next_page_offset}) rather than an offset into a bounded window, so a corpus larger than any
     * page limit is walked to its end instead of silently losing its tail.
     *
     * <p>The deadline bounds the whole walk rather than a single batch: what the caller needs protecting
     * against is a release parked on a collection that answers each batch slowly.
     *
     * @param sourceIndex source collection name
     * @param targetIndex snapshot collection name
     */
    @Override
    public void snapshotIndex(String sourceIndex, String targetIndex) {
        long deadline = System.currentTimeMillis() + snapshotTimeoutMs;
        // No alias is bound: a snapshot is addressed by its physical name precisely because the alias has to
        // keep pointing at the live collection the knowledge base goes on writing to.
        provisionCollection(targetIndex, readDimension(sourceIndex));
        long copied = copyPoints(sourceIndex, targetIndex, deadline);
        log.info("qdrant collection snapshotted, source={}, target={}, points={}",
                sourceIndex, targetIndex, copied);
    }

    /**
     * Reads the vector dimension of an existing collection.
     *
     * @param collection collection name
     * @return configured dimension
     */
    @SuppressWarnings("unchecked")
    private int readDimension(String collection) {
        Map<String, Object> response = get("/collections/" + collection, "describe collection");
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        Map<String, Object> config = (Map<String, Object>) result.get("config");
        Map<String, Object> params = (Map<String, Object>) config.get("params");
        Object vectors = params.get("vectors");
        if (!(vectors instanceof Map<?, ?> vectorConfig) || vectorConfig.get("size") == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "qdrant source collection carries no vector config");
        }
        return ((Number) vectorConfig.get("size")).intValue();
    }

    /**
     * Walks the source collection and writes every point into the target.
     *
     * @param sourceIndex source collection name
     * @param targetIndex target collection name
     * @param deadline    epoch millisecond the whole copy has to finish by
     * @return points copied
     */
    @SuppressWarnings("unchecked")
    private long copyPoints(String sourceIndex, String targetIndex, long deadline) {
        long copied = 0L;
        Object offset = null;
        while (true) {
            if (System.currentTimeMillis() >= deadline) {
                log.error("qdrant snapshot copy exceeded its budget, errorCode={}, source={}, copied={}",
                        ErrorCode.INTERNAL_ERROR, sourceIndex, copied);
                throw new BizException(ErrorCode.INTERNAL_ERROR, "qdrant snapshot copy timed out");
            }
            Map<String, Object> request = new HashMap<>();
            request.put("limit", COPY_BATCH_SIZE);
            request.put("with_payload", true);
            request.put("with_vector", true);
            if (offset != null) {
                request.put("offset", offset);
            }
            Map<String, Object> response =
                    post("/collections/" + sourceIndex + "/points/scroll", request, "scroll points");
            Map<String, Object> result = (Map<String, Object>) response.get("result");
            List<Map<String, Object>> batch = (List<Map<String, Object>>) result.get("points");
            if (CollectionUtils.isEmpty(batch)) {
                return copied;
            }
            List<Map<String, Object>> points = new ArrayList<>(batch.size());
            for (Map<String, Object> row : batch) {
                points.add(Map.of("id", row.get("id"), "vector", row.get("vector"), "payload", row.get("payload")));
            }
            put("/collections/" + targetIndex + "/points?wait=true", Map.of("points", points),
                    "write snapshot points");
            copied += batch.size();
            offset = result.get("next_page_offset");
            if (offset == null) {
                return copied;
            }
        }
    }

    @Override
    public void dropIndex(String physicalIndexName) {
        if (!indexExists(physicalIndexName)) {
            log.info("qdrant collection already absent, nothing to drop, collection={}", physicalIndexName);
            return;
        }
        client.delete()
                .uri("/collections/{name}", physicalIndexName)
                .retrieve()
                .toBodilessEntity();
        log.info("qdrant collection dropped, collection={}", physicalIndexName);
    }

    @Override
    public boolean indexExists(String physicalIndexName) {
        return Boolean.TRUE.equals(client.get()
                .uri("/collections/{name}", physicalIndexName)
                .exchange((request, response) -> response.getStatusCode().is2xxSuccessful(), false));
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<ScoredChunk> search(String alias, VectorQuery query) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("query", toFloatList(query.getQueryVector()));
        request.put("limit", query.getTopK());
        request.put("with_payload", List.of(IndexFields.CHUNK_ID));
        request.put("params", Map.of("hnsw_ef", SEARCH_HNSW_EF));
        List<Map<String, Object>> conditions = buildFilterConditions(query);
        if (!conditions.isEmpty()) {
            request.put("filter", Map.of("must", conditions));
        }
        Map<String, Object> response =
                post("/collections/" + alias + "/points/query", request, "search");
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        List<Map<String, Object>> points = (List<Map<String, Object>>) result.get("points");
        if (CollectionUtils.isEmpty(points)) {
            return List.of();
        }
        List<ScoredChunk> results = new ArrayList<>(points.size());
        for (Map<String, Object> point : points) {
            Map<String, Object> payload = (Map<String, Object>) point.get("payload");
            double score = ((Number) point.get("score")).doubleValue();
            results.add(new ScoredChunk(String.valueOf(payload.get(IndexFields.CHUNK_ID)),
                    VectorScoreNormalizer.fromQdrantScore(score), RetrievalSource.VECTOR));
        }
        return results;
    }

    @Override
    public HealthStatus healthCheck() {
        try {
            // /healthz answers in plain text rather than the JSON envelope every other endpoint uses.
            return Boolean.TRUE.equals(client.get()
                    .uri("/healthz")
                    .exchange((request, response) -> response.getStatusCode().is2xxSuccessful(), false))
                    ? HealthStatus.up("qdrant reachable")
                    : HealthStatus.down("qdrant responded with a failure status");
        } catch (Exception e) {
            log.error("qdrant health check failed, errorCode={}", ErrorCode.INTERNAL_ERROR, e);
            return HealthStatus.down("qdrant unreachable");
        }
    }

    /**
     * Points the alias at the collection, replacing whatever it pointed at before.
     *
     * <p>Both actions travel in one request: Qdrant applies an action list atomically, so a reader never
     * observes the alias as missing between the delete and the create.
     *
     * @param collection collection name
     * @param alias      alias name
     */
    private void bindAlias(String collection, String alias) {
        List<Map<String, Object>> actions = List.of(
                Map.of("delete_alias", Map.of("alias_name", alias)),
                Map.of("create_alias", Map.of("collection_name", collection, "alias_name", alias)));
        post("/collections/aliases", Map.of("actions", actions), "bind alias");
    }

    /**
     * Builds the filter Qdrant evaluates before the kNN search.
     *
     * <p>The mandatory knowledge base and enabled predicates come first, the optional caller supplied
     * predicates are appended with AND semantics, mirroring what the Elasticsearch adapter does with its
     * filter clause list so both engines narrow the candidate set identically.
     *
     * @param query kNN request
     * @return filter conditions, empty when nothing has to be narrowed
     */
    private List<Map<String, Object>> buildFilterConditions(VectorQuery query) {
        RetrievalFilter filter = query.getFilter();
        List<Map<String, Object>> conditions = new ArrayList<>();
        conditions.add(matchValue(IndexFields.KB_ID, filter.getKbId()));
        if (filter.isEnabledOnly()) {
            conditions.add(matchValue(IndexFields.ENABLED, true));
        }
        if (CollectionUtils.isNotEmpty(filter.getDocumentVersionIds())) {
            conditions.add(matchAny(IndexFields.DOCUMENT_VERSION_ID, filter.getDocumentVersionIds()));
        }
        appendMetadataConditions(conditions, filter.getMetadataFilter());
        return conditions;
    }

    /**
     * Appends the optional caller supplied predicates.
     *
     * @param conditions condition list being built
     * @param metadata   caller supplied filter, {@code null} or empty adds nothing
     */
    private void appendMetadataConditions(List<Map<String, Object>> conditions, MetadataFilter metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return;
        }
        if (CollectionUtils.isNotEmpty(metadata.getTagIds())) {
            // tag_ids holds an array, and match/any on an array payload matches when any element is listed.
            conditions.add(matchAny(IndexFields.TAG_IDS, metadata.getTagIds()));
        }
        if (isNotBlank(metadata.getSessionId())) {
            conditions.add(matchValue(IndexFields.SESSION_ID, metadata.getSessionId()));
        }
        if (isNotBlank(metadata.getSender())) {
            conditions.add(matchValue(IndexFields.SENDER, metadata.getSender()));
        }
        if (metadata.getMsgTimeFrom() != null || metadata.getMsgTimeTo() != null) {
            Map<String, Object> range = new LinkedHashMap<>();
            if (metadata.getMsgTimeFrom() != null) {
                range.put("gte", metadata.getMsgTimeFrom());
            }
            if (metadata.getMsgTimeTo() != null) {
                range.put("lte", metadata.getMsgTimeTo());
            }
            conditions.add(Map.of("key", IndexFields.MSG_TIME, "range", range));
        }
        if (MapUtils.isNotEmpty(metadata.getCustom())) {
            // Equality per entry, AND across entries; a match on an array payload matches when any
            // element equals, which is the "array contains" semantics of a keyword_match key.
            metadata.getCustom().forEach((key, value) ->
                    conditions.add(matchValue(IndexFields.EXT_PREFIX + key, value)));
        }
    }

    private Map<String, Object> matchValue(String field, Object value) {
        return Map.of("key", field, "match", Map.of("value", value));
    }

    private Map<String, Object> matchAny(String field, List<String> values) {
        return Map.of("key", field, "match", Map.of("any", values));
    }

    /**
     * Derives the point id of a chunk.
     *
     * <p>Deterministic by construction, so a write, a delete and a snapshot copy of the same chunk all
     * address the same point without any stored mapping.
     *
     * @param chunkId chunk business id
     * @return UUID string Qdrant accepts as a point id
     */
    private String pointId(String chunkId) {
        return UUID.nameUUIDFromBytes(chunkId.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /**
     * Builds the payload of a record.
     *
     * <p>The chunk id is carried here because the point id is a derived UUID: a search result is mapped
     * back to its chunk by reading this field, never by parsing the point id.
     *
     * @param record record being written
     * @return payload map
     */
    private Map<String, Object> toPayload(ChunkRecord record) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(IndexFields.CHUNK_ID, record.getChunkId());
        payload.put(IndexFields.KB_ID, record.getKbId());
        payload.put(IndexFields.DOC_ID, record.getDocId());
        payload.put(IndexFields.DOCUMENT_VERSION_ID, record.getDocumentVersionId());
        payload.put(IndexFields.PARENT_ID, nullToEmpty(record.getParentId()));
        payload.put(IndexFields.CHUNK_TYPE, nullToEmpty(record.getChunkType()));
        payload.put(IndexFields.ENABLED, record.isEnabled());
        payload.put(IndexFields.TAG_IDS, record.getTagIds() == null ? List.<String>of() : record.getTagIds());
        payload.put(IndexFields.SESSION_ID, nullToEmpty(record.getSessionId()));
        payload.put(IndexFields.SENDER, nullToEmpty(record.getSender()));
        payload.put(IndexFields.MSG_TIME, record.getMsgTime() == null ? 0L : record.getMsgTime());
        payload.put(IndexFields.CHUNK_SEQ, record.getChunkSeq() == null ? 0 : record.getChunkSeq());
        payload.put(IndexFields.CONTENT, truncate(record.getContent()));
        if (MapUtils.isNotEmpty(record.getExtMetadata())) {
            // Unindexed payload entries: Qdrant filters them by scanning, and the operator chosen key
            // set is unknown at collection provisioning time, so no per key index is created.
            payload.putAll(record.getExtMetadata());
        }
        return payload;
    }

    private List<Float> toFloatList(float[] vector) {
        List<Float> values = new ArrayList<>(vector.length);
        for (float value : vector) {
            values.add(value);
        }
        return values;
    }

    private boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String truncate(String content) {
        if (content == null) {
            return "";
        }
        return content.length() > CONTENT_MAX_LENGTH ? content.substring(0, CONTENT_MAX_LENGTH) : content;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, Object body, String action) {
        try {
            return client.post().uri(path).body(body).retrieve().body(Map.class);
        } catch (Exception e) {
            throw failure(action, e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> put(String path, Object body, String action) {
        try {
            return client.put().uri(path).body(body).retrieve().body(Map.class);
        } catch (Exception e) {
            throw failure(action, e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String path, String action) {
        try {
            return client.get().uri(path).retrieve().body(Map.class);
        } catch (Exception e) {
            throw failure(action, e);
        }
    }

    private BizException failure(String action, Exception cause) {
        if (cause instanceof BizException bizException) {
            return bizException;
        }
        log.error("qdrant {} failed, errorCode={}", action, ErrorCode.INTERNAL_ERROR, cause);
        return new BizException(ErrorCode.INTERNAL_ERROR, "qdrant " + action + " failed");
    }
}
