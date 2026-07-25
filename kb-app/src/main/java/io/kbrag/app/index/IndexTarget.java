package io.kbrag.app.index;

import io.kbrag.domain.enums.VectorEngine;

/**
 * One physical index a chunk has to be written to.
 *
 * <p>Modelling the write fan out as a list of targets is what keeps the three deployment shapes in
 * one code path: zero key has a single Elasticsearch target without a vector field, lite has a
 * single Elasticsearch target that also carries the vector, and full has an Elasticsearch target for
 * BM25 plus a Milvus target for the vector.
 *
 * @param engine            engine owning the target
 * @param physicalIndexName three segment physical name, the unit of the synchronization records
 * @param aliasName         alias reads and writes go through
 * @param embeddingSegment  embedding version segment used in the physical name, {@code bm25} for a
 *                          full mode Elasticsearch index and {@code none} in zero key mode
 * @param carriesVector     {@code true} when the target declares a vector field
 * @param dimension         vector dimension, 0 when the target carries no vector
 */
public record IndexTarget(VectorEngine engine,
                          String physicalIndexName,
                          String aliasName,
                          String embeddingSegment,
                          boolean carriesVector,
                          int dimension) {
}
