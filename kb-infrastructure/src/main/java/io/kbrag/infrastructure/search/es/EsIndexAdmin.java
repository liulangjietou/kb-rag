package io.kbrag.infrastructure.search.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.IndexOperation;
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
        List<String> staleIndices = client.indices()
                .getAlias(b -> b.name(alias).ignoreUnavailable(true))
                .result().keySet().stream()
                .filter(index -> !index.equals(target))
                .toList();

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
