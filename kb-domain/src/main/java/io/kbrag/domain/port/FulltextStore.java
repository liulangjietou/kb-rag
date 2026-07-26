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
 *
 * @author owlzhangfq@gmail.com
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
     * Flips the retrieval switch of records without touching any other field.
     *
     * <p>Separate from {@link #upsert} because an upsert replaces the whole document: in lite mode the
     * same document also carries the embedding, and rewriting it from a chunk row - which does not
     * store the vector - would silently erase it. Disabling a chunk must never cost a re-embedding, so
     * the flag travels on its own.
     *
     * <p>A chunk that has no document in the index is skipped rather than reported as a failure: a
     * parent chunk is never indexed and a chunk whose write is still pending has nothing to update.
     *
     * @param alias    alias of the target index
     * @param chunkIds chunk ids to update, ignored when empty
     * @param enabled  new retrieval switch value
     */
    void updateEnabled(String alias, List<String> chunkIds, boolean enabled);

    /**
     * Copies a physical index into a new, immutable one, requirement section 4.7 "index snapshot".
     *
     * <p>Addressed by physical name on both sides rather than by alias: the point of the operation is to
     * produce an index the alias does <em>not</em> point at, so that a released application version keeps
     * serving the corpus it was gated on while the live index goes on changing.
     *
     * <p>Implementations must be effectively synchronous - the caller freezes the resulting name into an
     * application version the moment this returns, so a copy that is still running would be published as
     * complete.
     *
     * @param sourceIndex physical name of the live index
     * @param targetIndex physical name of the snapshot to create
     */
    void snapshotIndex(String sourceIndex, String targetIndex);

    /**
     * Removes a physical index entirely, used to roll back a half built snapshot and to retire an expired
     * one.
     *
     * <p>Never called on a live index: an index an alias points at is retired by repointing the alias, and
     * dropping one would take the knowledge base offline.
     *
     * @param physicalIndexName physical index name, absent index treated as already removed
     */
    void dropIndex(String physicalIndexName);

    /**
     * Tells whether a physical index still exists.
     *
     * <p>The retrieval path asks before reading a snapshot index: a frozen name whose index was deleted out
     * of band has to degrade to the live alias rather than fail the call.
     *
     * @param physicalIndexName physical index name
     * @return {@code true} when the engine still holds it
     */
    boolean indexExists(String physicalIndexName);

    /**
     * Runs a BM25 search with the mandatory version and enabled filter applied engine side.
     *
     * @param alias alias of the target index, or the physical name of a snapshot index
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
