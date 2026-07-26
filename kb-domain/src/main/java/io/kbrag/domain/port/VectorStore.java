package io.kbrag.domain.port;

import io.kbrag.domain.model.ChunkRecord;
import io.kbrag.domain.model.HealthStatus;
import io.kbrag.domain.model.IndexSpec;
import io.kbrag.domain.model.ScoredChunk;
import io.kbrag.domain.model.VectorQuery;

import java.util.List;

/**
 * Outbound port of the vector route, implemented by Elasticsearch (lite mode) and Milvus (full
 * mode).
 *
 * <p><b>Score contract.</b> Both implementations must convert the engine specific score to the
 * standard cosine domain {@code [-1,1]} and then linearly map it to {@code [0,1]}:
 * Elasticsearch reports {@code (1+cos)/2} for a cosine dense_vector, so the implementation
 * restores {@code cos = score*2-1} before applying {@code (cos+1)/2}; Milvus reports the raw
 * cosine, which goes straight through the same final mapping. Every threshold in the retrieval
 * pipeline is applied to that normalised value, which is what makes the two engines behave
 * identically.
 *
 * @author owlzhangfq@gmail.com
 */
public interface VectorStore {

    /**
     * Engine code, {@code es} or {@code milvus}.
     *
     * @return engine code
     */
    String engine();

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
     * <p>Exists so that disabling a chunk never costs a re-embedding: the vector lives only inside the
     * engine, so replacing the whole record from its MySQL row would erase it.
     *
     * @param alias    alias of the target index
     * @param chunkIds chunk ids to update, ignored when empty
     * @param enabled  new retrieval switch value
     */
    void updateEnabled(String alias, List<String> chunkIds, boolean enabled);

    /**
     * Runs a kNN search with the mandatory version and enabled filter applied engine side.
     *
     * @param alias alias of the target index
     * @param query kNN request
     * @return candidates ordered by descending normalised score
     */
    List<ScoredChunk> search(String alias, VectorQuery query);

    /**
     * Probes engine connectivity.
     *
     * @return probe outcome
     */
    HealthStatus healthCheck();
}
