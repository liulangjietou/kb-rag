package io.kbrag.infrastructure.search.es;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.constant.IndexFields;
import io.kbrag.domain.enums.RetrievalSource;
import io.kbrag.domain.model.ChunkRecord;
import io.kbrag.domain.model.FulltextQuery;
import io.kbrag.domain.model.HealthStatus;
import io.kbrag.domain.model.IndexSpec;
import io.kbrag.domain.model.ScoredChunk;
import io.kbrag.domain.port.FulltextStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Elasticsearch backed BM25 route.
 *
 * <p>The raw BM25 score has no upper bound and is not comparable between queries, so it is returned
 * as is and only used for ordering; the fusion stage turns it into a rank.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EsFulltextStore implements FulltextStore {

    private final EsIndexAdmin indexAdmin;

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
    public List<ScoredChunk> searchBm25(String alias, FulltextQuery query) {
        List<Query> filters = indexAdmin.toFilters(query.getFilter());
        try {
            SearchResponse<Void> response = indexAdmin.client().search(request -> request
                    .index(alias)
                    .size(query.getTopK())
                    .source(source -> source.fetch(false))
                    .query(q -> q.bool(bool -> bool
                            .must(must -> must.match(match -> match
                                    .field(IndexFields.CONTENT)
                                    .query(query.getQueryText())))
                            .filter(filters))), Void.class);
            List<ScoredChunk> results = new ArrayList<>();
            for (Hit<Void> hit : response.hits().hits()) {
                double score = hit.score() == null ? 0.0d : hit.score();
                results.add(new ScoredChunk(hit.id(), score, RetrievalSource.BM25));
            }
            return results;
        } catch (Exception e) {
            log.error("bm25 search failed, errorCode={}, alias={}", ErrorCode.INTERNAL_ERROR, alias, e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "bm25 search failed", e);
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
}
