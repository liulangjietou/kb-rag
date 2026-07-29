package io.kbrag.domain.model;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

/**
 * Snapshot of the model configuration, consumed by the console to grey out model backed features.
 *
 * <p>The three capabilities are reported independently because they are configured independently: a
 * deployment can rerank without embedding, or embed without a chat model, and the console has to
 * disable exactly the controls that cannot work.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Builder
@ToString
public class ModelStatus {

    /** {@code false} means zero key mode: no embedding, BM25 single route retrieval. */
    private final boolean embeddingConfigured;

    /** Configured vector engine code, {@code es} or {@code qdrant}. */
    private final String vectorEngine;

    /** Embedding provider name, {@code none} when unconfigured. */
    private final String provider;

    /** Embedding model name, {@code none} when unconfigured. */
    private final String model;

    /** Vector dimension, 0 when unconfigured. */
    private final int dimension;

    /** {@code false} disables the rerank stage and greys out its switch. */
    private final boolean rerankConfigured;

    /** Rerank provider name, {@code none} when unconfigured. */
    private final String rerankProvider;

    /** Rerank model name, {@code none} when unconfigured. */
    private final String rerankModel;

    /** {@code false} disables the query rewrite stage and greys out its switch. */
    private final boolean chatConfigured;

    /** Chat provider name, {@code none} when unconfigured. */
    private final String chatProvider;

    /** Chat model name, {@code none} when unconfigured. */
    private final String chatModel;

    /** {@code false} means images are stored but carry no textual proxy. */
    private final boolean visionConfigured;

    /** Vision provider name, {@code none} when unconfigured. */
    private final String visionProvider;

    /** Vision model name, {@code none} when unconfigured. */
    private final String visionModel;

    /** {@code false} disables the multimodal page index switch and greys it out (M14 contract 6.2). */
    private final boolean multimodalConfigured;

    /** Multimodal embedding provider name, {@code none} when unconfigured. */
    private final String multimodalProvider;

    /** Multimodal embedding model name, {@code none} when unconfigured. */
    private final String multimodalModel;
}
