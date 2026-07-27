package io.kbrag.domain.model;

import io.kbrag.domain.enums.RetrievalSource;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

/**
 * One recalled chunk with the score already normalised by the store implementation.
 *
 * <p>Vector stores return the standard cosine similarity linearly mapped to {@code [0,1]}, so
 * scores are comparable across Elasticsearch and Qdrant. Full text stores return the raw BM25
 * score, which is only meaningful for ordering inside one query.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@AllArgsConstructor
@ToString
public class ScoredChunk {

    /** Chunk business id, used to load the fact source row from MySQL. */
    private final String chunkId;

    /** Normalised score, see the class javadoc for its meaning per route. */
    private final double score;

    /** Route that produced this candidate. */
    private final RetrievalSource source;
}
