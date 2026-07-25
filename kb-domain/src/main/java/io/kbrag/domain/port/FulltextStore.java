package io.kbrag.domain.port;

import io.kbrag.domain.model.ChunkRecord;
import io.kbrag.domain.model.FulltextQuery;
import io.kbrag.domain.model.HealthStatus;
import io.kbrag.domain.model.IndexSpec;
import io.kbrag.domain.model.ScoredChunk;

import java.util.List;

/**
 * Outbound port of the BM25 route, always backed by Elasticsearch.
 *
 * <p>In lite mode the same physical index also carries the vector field, but the two routes are
 * still issued as two independent requests so each one yields its own candidate set and raw
 * score; a single hybrid request would make the fusion stage impossible to implement.
 */
public interface FulltextStore {

    /**
     * Creates the physical index and points the alias at it, idempotently.
     *
     * @param spec physical index and alias description
     */
    void ensureIndex(IndexSpec spec);

    /**
     * Writes or overwrites records through the alias.
     *
     * @param alias   alias of the target index
     * @param records records to write, ignored when empty
     */
    void upsert(String alias, List<ChunkRecord> records);

    /**
     * Removes records through the alias.
     *
     * @param alias    alias of the target index
     * @param chunkIds chunk ids to remove, ignored when empty
     */
    void delete(String alias, List<String> chunkIds);

    /**
     * Runs a BM25 search with the mandatory version and enabled filter applied engine side.
     *
     * @param alias alias of the target index
     * @param query BM25 request
     * @return candidates ordered by descending raw BM25 score
     */
    List<ScoredChunk> searchBm25(String alias, FulltextQuery query);

    /**
     * Probes engine connectivity.
     *
     * @return probe outcome
     */
    HealthStatus healthCheck();
}
