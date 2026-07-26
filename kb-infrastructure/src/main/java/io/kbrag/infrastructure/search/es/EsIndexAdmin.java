package io.kbrag.infrastructure.search.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.bulk.IndexOperation;
import co.elastic.clients.elasticsearch.core.bulk.UpdateOperation;
import co.elastic.clients.elasticsearch.indices.update_aliases.Action;
import co.elastic.clients.json.JsonData;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.constant.IndexFields;
import io.kbrag.domain.model.ChunkRecord;
import io.kbrag.domain.model.IndexSpec;
import io.kbrag.domain.model.MetadataFilter;
import io.kbrag.domain.model.RetrievalFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Index administration and document writing shared by the Elasticsearch full text store and the
 * Elasticsearch vector store.
 *
 * <p>In lite mode both stores address the same physical index, so index creation, alias handling and
 * bulk writing live in one place and cannot drift apart.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EsIndexAdmin {

    private static final String META_SCHEMA_VERSION = "schema_version";
    private static final String SIMILARITY_COSINE = "cosine";
    private static final String ERROR_ALREADY_EXISTS = "resource_already_exists_exception";

    /** Bulk item status of a partial update whose document does not exist. */
    private static final int STATUS_NOT_FOUND = 404;

    private final ElasticsearchClient client;
    private final KbProperties properties;

    /**
     * Creates the physical index when missing and points the alias at it. Safe to call repeatedly.
     *
     * @param spec physical index and alias description
     */
    public void ensureIndex(IndexSpec spec) {
        try {
            boolean exists = client.indices().exists(b -> b.index(spec.getPhysicalIndexName())).value();
            if (!exists) {
                createIndex(spec);
            }
            pointAliasAtSingleIndex(spec);
            log.info("es index ready, index={}, alias={}, vectorField={}",
                    spec.getPhysicalIndexName(), spec.getAliasName(), spec.hasVectorField());
        } catch (Exception e) {
            log.error("ensure es index failed, errorCode={}, index={}",
                    ErrorCode.INTERNAL_ERROR, spec.getPhysicalIndexName(), e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "ensure elasticsearch index failed", e);
        }
    }

    /**
     * Clones a physical index into a new one, requirement section 4.7 "index snapshot".
     *
     * <p><b>Why a clone and not a reindex.</b> {@code _clone} hard links the Lucene segment files of the
     * source, so the copy costs no re-analysis, no re-embedding and almost no time regardless of corpus
     * size, and it reproduces the mapping - including the {@code dense_vector} field and its dimension -
     * byte for byte. A reindex would re-analyse every document and could produce a different BM25 baseline
     * than the one the release gate measured.
     *
     * <p><b>Why the source is blocked for writes.</b> Elasticsearch requires it: a clone hard links segments
     * and can only guarantee a consistent copy while nothing is being written. The block is the reason a
     * snapshot is a point in time rather than a smear across one, and it is what makes "the index the gate
     * measured is the index the release serves" true.
     *
     * <p><b>Why the block is always lifted.</b> The source is the live index the whole knowledge base reads
     * and writes through, so leaving it read only would take indexing offline for that base. The lift
     * therefore runs in a finally block: a failed snapshot must cost a failed release, never a frozen
     * knowledge base.
     *
     * <p>An already existing target is accepted as done rather than reported as a conflict, so retrying a
     * release whose bookkeeping failed after a successful clone is not blocked forever.
     *
     * @param sourceIndex physical name of the live index
     * @param targetIndex physical name of the snapshot to create
     */
    public void cloneIndex(String sourceIndex, String targetIndex) {
        try {
            blockWrites(sourceIndex, true);
            try {
                client.indices().clone(request -> request.index(sourceIndex).target(targetIndex));
                log.info("es index cloned, source={}, target={}", sourceIndex, targetIndex);
            } catch (Exception e) {
                if (!isAlreadyExists(e)) {
                    throw e;
                }
                log.info("es snapshot index already present, reused, target={}", targetIndex);
            }
            // The clone inherits the write block from its source, and a snapshot that cannot be written is
            // still expected to accept the enabled flag broadcast of a quality stop.
            blockWrites(targetIndex, false);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("clone es index failed, errorCode={}, source={}, target={}",
                    ErrorCode.INTERNAL_ERROR, sourceIndex, targetIndex, e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "clone elasticsearch index failed", e);
        } finally {
            unblockWritesQuietly(sourceIndex);
        }
    }

    /**
     * Deletes a physical index, treating an absent one as already deleted.
     *
     * @param physicalIndexName physical index name
     */
    public void deleteIndex(String physicalIndexName) {
        try {
            if (!indexExists(physicalIndexName)) {
                log.info("es index already absent, nothing to delete, index={}", physicalIndexName);
                return;
            }
            client.indices().delete(request -> request.index(physicalIndexName));
            log.info("es index deleted, index={}", physicalIndexName);
        } catch (Exception e) {
            log.error("delete es index failed, errorCode={}, index={}",
                    ErrorCode.INTERNAL_ERROR, physicalIndexName, e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "delete elasticsearch index failed", e);
        }
    }

    /**
     * Tells whether a physical index exists.
     *
     * @param physicalIndexName physical index name
     * @return {@code true} when the cluster holds it
     */
    public boolean indexExists(String physicalIndexName) {
        try {
            return client.indices().exists(request -> request.index(physicalIndexName)).value();
        } catch (Exception e) {
            log.error("es index existence check failed, errorCode={}, index={}",
                    ErrorCode.INTERNAL_ERROR, physicalIndexName, e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "elasticsearch index existence check failed", e);
        }
    }

    /**
     * Turns the write block of an index on or off.
     *
     * @param index   physical index name
     * @param blocked {@code true} makes the index reject writes
     * @throws IOException when the settings update call fails
     */
    private void blockWrites(String index, boolean blocked) throws IOException {
        client.indices().putSettings(request -> request
                .index(index)
                .settings(settings -> settings.blocks(blocks -> blocks.write(blocked))));
    }

    /**
     * Lifts the write block without letting the attempt mask the original failure.
     *
     * @param index physical index name
     */
    private void unblockWritesQuietly(String index) {
        try {
            blockWrites(index, false);
        } catch (Exception e) {
            // Reported at error level rather than thrown: the caller is either finishing successfully or
            // already carrying a more informative failure, and this one needs an operator either way -
            // the live index of a knowledge base stays read only until it is lifted by hand.
            log.error("es write block could not be lifted, errorCode={}, index={}",
                    ErrorCode.INTERNAL_ERROR, index, e);
        }
    }

    /**
     * Repoints the alias so that it resolves to exactly this physical index.
     *
     * <p>A plain putAlias only ever appends. As soon as the embedding version segment of the index
     * name changes - switching embedding model, or losing the API key so the segment falls back to
     * {@code none} - a second physical index joins the alias, Elasticsearch can no longer tell which
     * one to write to, and every write and delete through that alias fails permanently with "no
     * write index is defined for alias". So the alias is moved in one atomic update: it is removed
     * from whatever else it pointed at and added here as the write index. That is also exactly the
     * "new physical index plus atomic alias switch" the index contract asks for.
     *
     * @param spec physical index and alias description
     * @throws IOException when the alias update call fails
     */
    private void pointAliasAtSingleIndex(IndexSpec spec) throws IOException {
        String alias = spec.getAliasName();
        String target = spec.getPhysicalIndexName();
        // A brand new knowledge base has no alias yet, and get_alias answers 404 for a missing alias
        // rather than an empty result - ignoreUnavailable only covers missing indices. Asking whether
        // the alias exists first keeps the "nothing to detach" case on the normal path instead of
        // turning the first index creation of every new knowledge base into a failure.
        List<String> staleIndices = List.of();
        if (client.indices().existsAlias(b -> b.name(alias)).value()) {
            staleIndices = client.indices()
                    .getAlias(b -> b.name(alias))
                    .result().keySet().stream()
                    .filter(index -> !index.equals(target))
                    .toList();
        }

        List<Action> actions = new ArrayList<>(staleIndices.size() + 1);
        for (String stale : staleIndices) {
            actions.add(Action.of(a -> a.remove(r -> r.index(stale).alias(alias))));
        }
        actions.add(Action.of(a -> a.add(add -> add.index(target).alias(alias).isWriteIndex(true))));
        client.indices().updateAliases(builder -> builder.actions(actions));
        if (!CollectionUtils.isEmpty(staleIndices)) {
            log.info("es alias repointed, alias={}, target={}, detached={}", alias, target, staleIndices);
        }
    }

    /**
     * Writes records through the alias, overwriting documents with the same chunk id.
     *
     * @param alias   alias of the target index
     * @param records records to write
     */
    public void bulkUpsert(String alias, List<ChunkRecord> records) {
        if (CollectionUtils.isEmpty(records)) {
            return;
        }
        List<BulkOperation> operations = new ArrayList<>(records.size());
        for (ChunkRecord record : records) {
            IndexOperation<Map<String, Object>> operation = new IndexOperation.Builder<Map<String, Object>>()
                    .index(alias)
                    .id(record.getChunkId())
                    .document(toDocument(record))
                    .build();
            operations.add(BulkOperation.of(op -> op.index(operation)));
        }
        executeBulk(alias, operations, "upsert");
    }

    /**
     * Flips the retrieval switch of documents through the alias, leaving every other field untouched.
     *
     * <p>A partial update rather than a write: in lite mode the document also holds the embedding, and
     * an index operation built from a chunk row would drop the vector field and silently remove the
     * chunk from the vector route.
     *
     * <p><b>A missing document is not a failure.</b> Elasticsearch answers a partial update of an
     * absent id with {@code document_missing_exception}, and the ids handed over here legitimately
     * include chunks that were never indexed - a parent chunk is stored in MySQL only, and a chunk
     * whose write is still pending has nothing to update yet. Those items are counted and logged; any
     * other error still fails the call.
     *
     * @param alias    alias of the target index
     * @param chunkIds chunk ids to update
     * @param enabled  new retrieval switch value
     */
    public void bulkUpdateEnabled(String alias, List<String> chunkIds, boolean enabled) {
        if (CollectionUtils.isEmpty(chunkIds)) {
            return;
        }
        Map<String, Object> partial = new HashMap<>();
        partial.put(IndexFields.ENABLED, enabled);
        List<BulkOperation> operations = new ArrayList<>(chunkIds.size());
        for (String chunkId : chunkIds) {
            UpdateOperation<Void, Map<String, Object>> operation =
                    new UpdateOperation.Builder<Void, Map<String, Object>>()
                            .index(alias)
                            .id(chunkId)
                            .action(action -> action.doc(partial))
                            .build();
            operations.add(BulkOperation.of(op -> op.update(operation)));
        }
        executeTolerantBulk(alias, operations, enabled);
    }

    /**
     * Removes documents through the alias.
     *
     * @param alias    alias of the target index
     * @param chunkIds chunk ids to remove
     */
    public void bulkDelete(String alias, List<String> chunkIds) {
        if (CollectionUtils.isEmpty(chunkIds)) {
            return;
        }
        List<BulkOperation> operations = new ArrayList<>(chunkIds.size());
        for (String chunkId : chunkIds) {
            operations.add(BulkOperation.of(op -> op.delete(del -> del.index(alias).id(chunkId))));
        }
        executeBulk(alias, operations, "delete");
    }

    /**
     * Translates the retrieval filter into Elasticsearch filter clauses.
     *
     * <p>Version isolation and the enabled switch are applied engine side, never after the fact, so
     * a chunk of a non visible version can never be recalled. The optional metadata predicates are
     * appended to the same clause list rather than applied to the recalled candidates, which is what
     * keeps {@code recall_top_k} meaningful: post filtering would return fewer candidates than the
     * caller asked for and would bias the fusion stage towards whichever route survived the cut.
     *
     * @param filter retrieval filter
     * @return filter clauses, all combined with AND semantics
     */
    public List<Query> toFilters(RetrievalFilter filter) {
        List<Query> filters = new ArrayList<>();
        filters.add(Query.of(q -> q.term(t -> t.field(IndexFields.KB_ID).value(filter.getKbId()))));
        if (filter.isEnabledOnly()) {
            filters.add(Query.of(q -> q.term(t -> t.field(IndexFields.ENABLED).value(true))));
        }
        if (CollectionUtils.isNotEmpty(filter.getDocumentVersionIds())) {
            filters.add(termsQuery(IndexFields.DOCUMENT_VERSION_ID, filter.getDocumentVersionIds()));
        }
        appendMetadataFilters(filters, filter.getMetadataFilter());
        return filters;
    }

    /**
     * Appends the optional caller supplied predicates.
     *
     * @param filters  clause list being built
     * @param metadata caller supplied filter, {@code null} or empty adds nothing
     */
    private void appendMetadataFilters(List<Query> filters, MetadataFilter metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return;
        }
        if (CollectionUtils.isNotEmpty(metadata.getTagIds())) {
            filters.add(termsQuery(IndexFields.TAG_IDS, metadata.getTagIds()));
        }
        if (metadata.getSessionId() != null && !metadata.getSessionId().isBlank()) {
            String sessionId = metadata.getSessionId();
            filters.add(Query.of(q -> q.term(t -> t.field(IndexFields.SESSION_ID).value(sessionId))));
        }
        if (metadata.getSender() != null && !metadata.getSender().isBlank()) {
            String sender = metadata.getSender();
            filters.add(Query.of(q -> q.term(t -> t.field(IndexFields.SENDER).value(sender))));
        }
        if (metadata.getMsgTimeFrom() != null || metadata.getMsgTimeTo() != null) {
            Long from = metadata.getMsgTimeFrom();
            Long to = metadata.getMsgTimeTo();
            filters.add(Query.of(q -> q.range(r -> {
                r.field(IndexFields.MSG_TIME);
                if (from != null) {
                    r.gte(JsonData.of(from));
                }
                if (to != null) {
                    r.lte(JsonData.of(to));
                }
                return r;
            })));
        }
    }

    private Query termsQuery(String field, List<String> values) {
        List<FieldValue> fieldValues = values.stream().map(FieldValue::of).toList();
        return Query.of(q -> q.terms(t -> t.field(field).terms(tv -> tv.value(fieldValues))));
    }

    /**
     * Exposes the underlying client to the store implementations.
     *
     * @return Elasticsearch client
     */
    public ElasticsearchClient client() {
        return client;
    }

    /**
     * Creates the index, falling back to the standard analyzer when the configured one is not
     * installed. Detecting the missing plugin by attempting the creation keeps the deployment working
     * whether or not the ik plugin is present.
     *
     * @param spec physical index and alias description
     * @throws Exception when the index cannot be created with either analyzer
     */
    private void createIndex(IndexSpec spec) throws Exception {
        String configured = properties.getEs().getContentAnalyzer();
        try {
            doCreate(spec, configured);
        } catch (Exception first) {
            if (isAlreadyExists(first)) {
                return;
            }
            String fallback = properties.getEs().getFallbackAnalyzer();
            log.info("content analyzer unavailable, falling back, index={}, configured={}, fallback={}",
                    spec.getPhysicalIndexName(), configured, fallback);
            // The fallback stays permanent rather than becoming an error once ik is available: the
            // dictionary is served over HTTP from t_kb_ik_dict, but the plugin itself is an optional
            // deployment step, and a cluster without it must still be able to create an index.
            doCreate(spec, fallback);
        }
    }

    private void doCreate(IndexSpec spec, String analyzer) throws Exception {
        Map<String, Property> mappingProperties = buildProperties(spec, analyzer);
        String shards = String.valueOf(properties.getEs().getNumberOfShards());
        String replicas = String.valueOf(properties.getEs().getNumberOfReplicas());
        client.indices().create(request -> request
                .index(spec.getPhysicalIndexName())
                .settings(settings -> settings
                        .numberOfShards(shards)
                        .numberOfReplicas(replicas))
                .mappings(mappings -> mappings
                        .properties(mappingProperties)
                        .meta(META_SCHEMA_VERSION, JsonData.of(spec.getSchemaVersion()))));
        log.info("es index created, index={}, analyzer={}", spec.getPhysicalIndexName(), analyzer);
    }

    /**
     * Declares the engine side field set. This is the complete list of filterable fields; adding one
     * is a schema change and requires a rebuild with an alias switch.
     *
     * @param spec     physical index description
     * @param analyzer analyzer of the content field
     * @return field name to property mapping
     */
    private Map<String, Property> buildProperties(IndexSpec spec, String analyzer) {
        Map<String, Property> mapping = new LinkedHashMap<>();
        mapping.put(IndexFields.CHUNK_ID, keyword());
        mapping.put(IndexFields.KB_ID, keyword());
        mapping.put(IndexFields.DOC_ID, keyword());
        mapping.put(IndexFields.DOCUMENT_VERSION_ID, keyword());
        mapping.put(IndexFields.PARENT_ID, keyword());
        mapping.put(IndexFields.CHUNK_TYPE, keyword());
        mapping.put(IndexFields.ENABLED, Property.of(p -> p.boolean_(b -> b)));
        // Elasticsearch treats every field as multi valued, so a keyword field carries the tag array.
        mapping.put(IndexFields.TAG_IDS, keyword());
        mapping.put(IndexFields.SESSION_ID, keyword());
        mapping.put(IndexFields.SENDER, keyword());
        mapping.put(IndexFields.MSG_TIME, Property.of(p -> p.long_(l -> l)));
        mapping.put(IndexFields.CHUNK_SEQ, Property.of(p -> p.integer(i -> i)));
        mapping.put(IndexFields.CONTENT, Property.of(p -> p.text(t -> t.analyzer(analyzer))));
        if (spec.hasVectorField()) {
            Integer dimension = spec.getDimension();
            mapping.put(IndexFields.VECTOR, Property.of(p -> p.denseVector(v -> v
                    .dims(dimension)
                    .index(true)
                    .similarity(SIMILARITY_COSINE))));
        }
        return mapping;
    }

    private Property keyword() {
        return Property.of(p -> p.keyword(k -> k));
    }

    private Map<String, Object> toDocument(ChunkRecord record) {
        Map<String, Object> document = new HashMap<>();
        document.put(IndexFields.CHUNK_ID, record.getChunkId());
        document.put(IndexFields.KB_ID, record.getKbId());
        document.put(IndexFields.DOC_ID, record.getDocId());
        document.put(IndexFields.DOCUMENT_VERSION_ID, record.getDocumentVersionId());
        document.put(IndexFields.PARENT_ID, record.getParentId());
        document.put(IndexFields.CHUNK_TYPE, record.getChunkType());
        document.put(IndexFields.ENABLED, record.isEnabled());
        document.put(IndexFields.TAG_IDS, record.getTagIds());
        document.put(IndexFields.SESSION_ID, record.getSessionId());
        document.put(IndexFields.SENDER, record.getSender());
        document.put(IndexFields.MSG_TIME, record.getMsgTime());
        document.put(IndexFields.CHUNK_SEQ, record.getChunkSeq());
        document.put(IndexFields.CONTENT, record.getContent());
        if (record.getVector() != null) {
            document.put(IndexFields.VECTOR, toFloatList(record.getVector()));
        }
        return document;
    }

    private List<Float> toFloatList(float[] vector) {
        List<Float> values = new ArrayList<>(vector.length);
        for (float value : vector) {
            values.add(value);
        }
        return values;
    }

    private void executeBulk(String alias, List<BulkOperation> operations, String action) {
        try {
            BulkResponse response = client.bulk(BulkRequest.of(b -> b
                    .operations(operations)
                    .refresh(Refresh.True)));
            if (response.errors()) {
                String firstError = response.items().stream()
                        .filter(item -> item.error() != null)
                        .map(item -> item.error().reason())
                        .findFirst()
                        .orElse("unknown bulk error");
                log.error("es bulk {} failed, errorCode={}, alias={}, reason={}",
                        action, ErrorCode.INTERNAL_ERROR, alias, firstError);
                throw new BizException(ErrorCode.INTERNAL_ERROR, "elasticsearch bulk " + action + " failed");
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("es bulk {} failed, errorCode={}, alias={}", action, ErrorCode.INTERNAL_ERROR, alias, e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "elasticsearch bulk " + action + " failed", e);
        }
    }

    /**
     * Runs a bulk of partial updates, treating an absent document as a skip.
     *
     * @param alias      alias the operations address
     * @param operations bulk operations
     * @param enabled    value being written, logged for traceability
     */
    private void executeTolerantBulk(String alias, List<BulkOperation> operations, boolean enabled) {
        try {
            BulkResponse response = client.bulk(BulkRequest.of(b -> b
                    .operations(operations)
                    .refresh(Refresh.True)));
            int missing = 0;
            String blockingError = null;
            for (BulkResponseItem item : response.items()) {
                if (item.error() == null) {
                    continue;
                }
                if (item.status() == STATUS_NOT_FOUND) {
                    missing++;
                    continue;
                }
                blockingError = item.error().reason();
                break;
            }
            if (blockingError != null) {
                log.error("es enabled flag sync failed, errorCode={}, alias={}, reason={}",
                        ErrorCode.INTERNAL_ERROR, alias, blockingError);
                throw new BizException(ErrorCode.INTERNAL_ERROR, "elasticsearch enabled flag sync failed");
            }
            log.info("es enabled flag synced, alias={}, enabled={}, chunks={}, notIndexed={}",
                    alias, enabled, operations.size(), missing);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("es enabled flag sync failed, errorCode={}, alias={}", ErrorCode.INTERNAL_ERROR, alias, e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "elasticsearch enabled flag sync failed", e);
        }
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
}
