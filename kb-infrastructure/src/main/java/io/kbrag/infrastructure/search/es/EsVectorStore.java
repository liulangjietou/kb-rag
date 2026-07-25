package io.kbrag.infrastructure.search.es;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Elasticsearch backed vector route, used by the lite deployment where one engine serves both
 * routes.
 *
 * <p>Score conversion. A {@code dense_vector} field with cosine similarity makes Elasticsearch
 * report {@code (1 + cos) / 2}. The implementation restores the standard cosine value and applies
 * the shared mapping to {@code [0,1]}, which is the same two step conversion the Milvus
 * implementation performs on its raw cosine score. Writing it out explicitly is what guarantees a
 * score threshold means the same thing in both deployment modes.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "kb.vector", name = "engine", havingValue = "es", matchIfMissing = true)
public class EsVectorStore implements VectorStore {

    /** Multiplier applied to {@code topK} to size the kNN candidate pool. */
    private static final int CANDIDATE_FACTOR = 4;

    private final EsIndexAdmin indexAdmin;

    @Override
    public String engine() {
        return VectorEngine.ES.code();
    }

    @Override
    public void ensureIndex(IndexSpec spec) {
        indexAdmin.ensureIndex(spec);
    }

    @Override
    public void upsert(String alias, List<ChunkRecord> records) {
        indexAdmin.bulkUpsert(alias, records);
    }

    @Override
    public void delete(String alias, List<String> chunkIds) {
        indexAdmin.bulkDelete(alias, chunkIds);
    }

    @Override
    public List<ScoredChunk> search(String alias, VectorQuery query) {
        List<Query> filters = indexAdmin.toFilters(query.getFilter());
        List<Float> queryVector = toFloatList(query.getQueryVector());
        long topK = query.getTopK();
        long candidates = topK * CANDIDATE_FACTOR;
        try {
            SearchResponse<Void> response = indexAdmin.client().search(request -> request
                    .index(alias)
                    .size(query.getTopK())
                    .source(source -> source.fetch(false))
                    .knn(knn -> knn
                            .field(IndexFields.VECTOR)
                            .queryVector(queryVector)
                            .k(topK)
                            .numCandidates(candidates)
                            .filter(filters)), Void.class);
            List<ScoredChunk> results = new ArrayList<>();
            for (Hit<Void> hit : response.hits().hits()) {
                double rawScore = hit.score() == null ? 0.0d : hit.score();
                results.add(new ScoredChunk(hit.id(),
                        VectorScoreNormalizer.fromElasticsearchScore(rawScore), RetrievalSource.VECTOR));
            }
            return results;
        } catch (Exception e) {
            log.error("vector search failed, errorCode={}, alias={}", ErrorCode.INTERNAL_ERROR, alias, e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "vector search failed", e);
        }
    }

    @Override
    public HealthStatus healthCheck() {
        try {
            indexAdmin.client().ping();
            return HealthStatus.up("elasticsearch reachable");
        } catch (Exception e) {
            log.error("elasticsearch health check failed, errorCode={}", ErrorCode.INTERNAL_ERROR, e);
            return HealthStatus.down("elasticsearch unreachable");
        }
    }

    private List<Float> toFloatList(float[] vector) {
        List<Float> values = new ArrayList<>(vector.length);
        for (float value : vector) {
            values.add(value);
        }
        return values;
    }
}
