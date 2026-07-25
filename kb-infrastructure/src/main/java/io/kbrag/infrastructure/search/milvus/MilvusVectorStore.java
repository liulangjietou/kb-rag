package io.kbrag.infrastructure.search.milvus;

import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.constant.IndexFields;
import io.kbrag.domain.enums.RetrievalSource;
import io.kbrag.domain.enums.VectorEngine;
import io.kbrag.domain.model.ChunkRecord;
import io.kbrag.domain.model.HealthStatus;
import io.kbrag.domain.model.IndexSpec;
import io.kbrag.domain.model.ScoredChunk;
import io.kbrag.domain.model.VectorQuery;
import io.kbrag.domain.port.VectorStore;
import io.kbrag.domain.service.VectorScoreNormalizer;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.SearchResults;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.alias.AlterAliasParam;
import io.milvus.param.alias.CreateAliasParam;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.dml.UpsertParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.SearchResultsWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Milvus backed vector route, used by the full deployment where Elasticsearch only serves BM25.
 *
 * <p>Score conversion. Milvus returns the raw cosine similarity in {@code [-1,1]}, so the
 * implementation only applies the shared linear mapping to {@code [0,1]}. Combined with the
 * Elasticsearch implementation, which first restores the raw cosine from its own {@code (1+cos)/2}
 * score, a score threshold means exactly the same thing in both deployment modes.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "kb.vector", name = "engine", havingValue = "milvus")
public class MilvusVectorStore implements VectorStore {

    private static final int VARCHAR_MAX_LENGTH = 64;
    private static final int CONTENT_MAX_LENGTH = 65535;
    private static final int TAG_MAX_CAPACITY = 32;
    private static final String HNSW_EXTRA_PARAM = "{\"M\":16,\"efConstruction\":200}";
    private static final String SEARCH_EXTRA_PARAM = "{\"ef\":64}";
    private static final String VECTOR_INDEX_NAME = "idx_vector";

    private final MilvusServiceClient client;

    @Override
    public String engine() {
        return VectorEngine.MILVUS.code();
    }

    @Override
    public void ensureIndex(IndexSpec spec) {
        if (!spec.hasVectorField()) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "milvus collection requires a vector dimension");
        }
        String collection = spec.getPhysicalIndexName();
        R<Boolean> exists = client.hasCollection(HasCollectionParam.newBuilder()
                .withCollectionName(collection)
                .build());
        checkResponse(exists, "has collection");
        if (!Boolean.TRUE.equals(exists.getData())) {
            createCollection(collection, spec.getDimension());
            createVectorIndex(collection);
            createScalarIndexes(collection);
        }
        client.loadCollection(LoadCollectionParam.newBuilder().withCollectionName(collection).build());
        bindAlias(collection, spec.getAliasName());
        log.info("milvus collection ready, collection={}, alias={}", collection, spec.getAliasName());
    }

    @Override
    public void upsert(String alias, List<ChunkRecord> records) {
        if (CollectionUtils.isEmpty(records)) {
            return;
        }
        List<InsertParam.Field> fields = new ArrayList<>();
        fields.add(new InsertParam.Field(IndexFields.CHUNK_ID,
                records.stream().map(ChunkRecord::getChunkId).collect(Collectors.toList())));
        fields.add(new InsertParam.Field(IndexFields.KB_ID,
                records.stream().map(ChunkRecord::getKbId).collect(Collectors.toList())));
        fields.add(new InsertParam.Field(IndexFields.DOC_ID,
                records.stream().map(ChunkRecord::getDocId).collect(Collectors.toList())));
        fields.add(new InsertParam.Field(IndexFields.DOCUMENT_VERSION_ID,
                records.stream().map(ChunkRecord::getDocumentVersionId).collect(Collectors.toList())));
        fields.add(new InsertParam.Field(IndexFields.PARENT_ID,
                records.stream().map(record -> nullToEmpty(record.getParentId())).collect(Collectors.toList())));
        fields.add(new InsertParam.Field(IndexFields.CHUNK_TYPE,
                records.stream().map(record -> nullToEmpty(record.getChunkType())).collect(Collectors.toList())));
        fields.add(new InsertParam.Field(IndexFields.ENABLED,
                records.stream().map(ChunkRecord::isEnabled).collect(Collectors.toList())));
        fields.add(new InsertParam.Field(IndexFields.TAG_IDS,
                records.stream().map(record -> record.getTagIds() == null
                        ? List.<String>of() : record.getTagIds()).collect(Collectors.toList())));
        fields.add(new InsertParam.Field(IndexFields.SESSION_ID,
                records.stream().map(record -> nullToEmpty(record.getSessionId())).collect(Collectors.toList())));
        fields.add(new InsertParam.Field(IndexFields.SENDER,
                records.stream().map(record -> nullToEmpty(record.getSender())).collect(Collectors.toList())));
        fields.add(new InsertParam.Field(IndexFields.MSG_TIME,
                records.stream().map(record -> record.getMsgTime() == null ? 0L : record.getMsgTime())
                        .collect(Collectors.toList())));
        fields.add(new InsertParam.Field(IndexFields.CHUNK_SEQ,
                records.stream().map(record -> record.getChunkSeq() == null ? 0 : record.getChunkSeq())
                        .collect(Collectors.toList())));
        fields.add(new InsertParam.Field(IndexFields.CONTENT,
                records.stream().map(record -> truncate(record.getContent())).collect(Collectors.toList())));
        fields.add(new InsertParam.Field(IndexFields.VECTOR,
                records.stream().map(record -> toFloatList(record.getVector())).collect(Collectors.toList())));

        R<MutationResult> response = client.upsert(UpsertParam.newBuilder()
                .withCollectionName(alias)
                .withFields(fields)
                .build());
        checkResponse(response, "upsert");
    }

    @Override
    public void delete(String alias, List<String> chunkIds) {
        if (CollectionUtils.isEmpty(chunkIds)) {
            return;
        }
        R<MutationResult> response = client.delete(DeleteParam.newBuilder()
                .withCollectionName(alias)
                .withExpr(inExpression(IndexFields.CHUNK_ID, chunkIds))
                .build());
        checkResponse(response, "delete");
    }

    @Override
    public List<ScoredChunk> search(String alias, VectorQuery query) {
        SearchParam param = SearchParam.newBuilder()
                .withCollectionName(alias)
                .withMetricType(MetricType.COSINE)
                .withOutFields(List.of(IndexFields.CHUNK_ID))
                .withTopK(query.getTopK())
                .withVectors(List.of(toFloatList(query.getQueryVector())))
                .withVectorFieldName(IndexFields.VECTOR)
                .withExpr(buildFilterExpression(query))
                .withParams(SEARCH_EXTRA_PARAM)
                .build();
        R<SearchResults> response = client.search(param);
        checkResponse(response, "search");
        SearchResultsWrapper wrapper = new SearchResultsWrapper(response.getData().getResults());
        List<SearchResultsWrapper.IDScore> hits = wrapper.getIDScore(0);
        List<ScoredChunk> results = new ArrayList<>(hits.size());
        for (SearchResultsWrapper.IDScore hit : hits) {
            results.add(new ScoredChunk(hit.getStrID(),
                    VectorScoreNormalizer.fromMilvusScore(hit.getScore()), RetrievalSource.VECTOR));
        }
        return results;
    }

    @Override
    public HealthStatus healthCheck() {
        try {
            R<?> version = client.getVersion();
            return version.getStatus() == R.Status.Success.getCode()
                    ? HealthStatus.up("milvus reachable")
                    : HealthStatus.down("milvus responded with status " + version.getStatus());
        } catch (Exception e) {
            log.error("milvus health check failed, errorCode={}", ErrorCode.INTERNAL_ERROR, e);
            return HealthStatus.down("milvus unreachable");
        }
    }

    private void createCollection(String collection, int dimension) {
        List<FieldType> fields = new ArrayList<>();
        fields.add(FieldType.newBuilder().withName(IndexFields.CHUNK_ID).withDataType(DataType.VarChar)
                .withMaxLength(VARCHAR_MAX_LENGTH).withPrimaryKey(true).withAutoID(false).build());
        fields.add(varchar(IndexFields.KB_ID));
        fields.add(varchar(IndexFields.DOC_ID));
        fields.add(varchar(IndexFields.DOCUMENT_VERSION_ID));
        fields.add(varchar(IndexFields.PARENT_ID));
        fields.add(varchar(IndexFields.CHUNK_TYPE));
        fields.add(FieldType.newBuilder().withName(IndexFields.ENABLED)
                .withDataType(DataType.Bool).build());
        fields.add(FieldType.newBuilder().withName(IndexFields.TAG_IDS)
                .withDataType(DataType.Array)
                .withElementType(DataType.VarChar)
                .withMaxCapacity(TAG_MAX_CAPACITY)
                .withMaxLength(VARCHAR_MAX_LENGTH)
                .build());
        fields.add(varchar(IndexFields.SESSION_ID));
        fields.add(varchar(IndexFields.SENDER));
        fields.add(FieldType.newBuilder().withName(IndexFields.MSG_TIME)
                .withDataType(DataType.Int64).build());
        fields.add(FieldType.newBuilder().withName(IndexFields.CHUNK_SEQ)
                .withDataType(DataType.Int32).build());
        fields.add(FieldType.newBuilder().withName(IndexFields.CONTENT)
                .withDataType(DataType.VarChar).withMaxLength(CONTENT_MAX_LENGTH).build());
        fields.add(FieldType.newBuilder().withName(IndexFields.VECTOR)
                .withDataType(DataType.FloatVector).withDimension(dimension).build());

        R<?> response = client.createCollection(CreateCollectionParam.newBuilder()
                .withCollectionName(collection)
                .withFieldTypes(fields)
                .withEnableDynamicField(false)
                .build());
        checkResponse(response, "create collection");
    }

    private void createVectorIndex(String collection) {
        R<?> response = client.createIndex(CreateIndexParam.newBuilder()
                .withCollectionName(collection)
                .withFieldName(IndexFields.VECTOR)
                .withIndexName(VECTOR_INDEX_NAME)
                .withIndexType(IndexType.HNSW)
                .withMetricType(MetricType.COSINE)
                .withExtraParam(HNSW_EXTRA_PARAM)
                .build());
        checkResponse(response, "create vector index");
    }

    /**
     * Creates scalar indexes on the mandatory filter fields.
     *
     * <p>Filtering works without them, they only keep the mandatory version and enabled predicates
     * cheap, so a failure is logged and does not abort index creation.
     *
     * @param collection collection name
     */
    private void createScalarIndexes(String collection) {
        List<String> scalarFields = List.of(IndexFields.KB_ID, IndexFields.DOC_ID,
                IndexFields.DOCUMENT_VERSION_ID, IndexFields.ENABLED);
        for (String field : scalarFields) {
            try {
                R<?> response = client.createIndex(CreateIndexParam.newBuilder()
                        .withCollectionName(collection)
                        .withFieldName(field)
                        .withIndexName("idx_" + field)
                        .withIndexType(IndexType.INVERTED)
                        .build());
                if (response.getStatus() != R.Status.Success.getCode()) {
                    log.info("skip scalar index, collection={}, field={}, status={}",
                            collection, field, response.getStatus());
                }
            } catch (Exception e) {
                log.info("skip scalar index, collection={}, field={}, reason={}",
                        collection, field, e.getMessage());
            }
        }
    }

    private void bindAlias(String collection, String alias) {
        R<?> created = client.createAlias(CreateAliasParam.newBuilder()
                .withCollectionName(collection)
                .withAlias(alias)
                .build());
        if (created.getStatus() == R.Status.Success.getCode()) {
            return;
        }
        R<?> altered = client.alterAlias(AlterAliasParam.newBuilder()
                .withCollectionName(collection)
                .withAlias(alias)
                .build());
        checkResponse(altered, "alter alias");
    }

    private String buildFilterExpression(VectorQuery query) {
        List<String> predicates = new ArrayList<>();
        predicates.add(IndexFields.KB_ID + " == \"" + query.getFilter().getKbId() + "\"");
        if (query.getFilter().isEnabledOnly()) {
            predicates.add(IndexFields.ENABLED + " == true");
        }
        if (CollectionUtils.isNotEmpty(query.getFilter().getDocumentVersionIds())) {
            predicates.add(inExpression(IndexFields.DOCUMENT_VERSION_ID,
                    query.getFilter().getDocumentVersionIds()));
        }
        return String.join(" && ", predicates);
    }

    private String inExpression(String field, List<String> values) {
        String literals = values.stream()
                .map(value -> "\"" + value + "\"")
                .collect(Collectors.joining(", "));
        return field + " in [" + literals + "]";
    }

    private FieldType varchar(String name) {
        return FieldType.newBuilder().withName(name).withDataType(DataType.VarChar)
                .withMaxLength(VARCHAR_MAX_LENGTH).build();
    }

    private List<Float> toFloatList(float[] vector) {
        List<Float> values = new ArrayList<>(vector.length);
        for (float value : vector) {
            values.add(value);
        }
        return values;
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

    private void checkResponse(R<?> response, String action) {
        if (response.getStatus() != R.Status.Success.getCode()) {
            log.error("milvus {} failed, errorCode={}, status={}, message={}",
                    action, ErrorCode.INTERNAL_ERROR, response.getStatus(), response.getMessage());
            throw new BizException(ErrorCode.INTERNAL_ERROR, "milvus " + action + " failed");
        }
    }
}
