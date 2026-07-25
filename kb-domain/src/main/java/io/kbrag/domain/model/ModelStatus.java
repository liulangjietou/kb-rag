package io.kbrag.domain.model;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

/**
 * Snapshot of the model configuration, consumed by the console to grey out model backed features.
 */
@Getter
@Builder
@ToString
public class ModelStatus {

    /** {@code false} means zero key mode: no embedding, BM25 single route retrieval. */
    private final boolean embeddingConfigured;

    /** Configured vector engine code, {@code es} or {@code milvus}. */
    private final String vectorEngine;

    /** Embedding provider name, {@code none} when unconfigured. */
    private final String provider;

    /** Embedding model name, {@code none} when unconfigured. */
    private final String model;

    /** Vector dimension, 0 when unconfigured. */
    private final int dimension;
}
