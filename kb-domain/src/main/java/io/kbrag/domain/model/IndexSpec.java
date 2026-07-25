package io.kbrag.domain.model;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

/**
 * Declarative description of one physical index plus the alias that points at it.
 *
 * <p>A {@code null} {@link #dimension} means the index carries no vector field, which is the zero
 * key case and also the Elasticsearch BM25 index of the full mode.
 */
@Getter
@Builder
@ToString
public class IndexSpec {

    /** Three segment physical index or collection name. */
    private final String physicalIndexName;

    /** Alias every read and write goes through. */
    private final String aliasName;

    /** Vector dimension, {@code null} when the index has no vector field. */
    private final Integer dimension;

    /** Engine schema version stored alongside the index. */
    private final String schemaVersion;

    /**
     * Tells whether this index declares a vector field.
     *
     * @return {@code true} when a positive dimension is declared
     */
    public boolean hasVectorField() {
        return dimension != null && dimension > 0;
    }
}
