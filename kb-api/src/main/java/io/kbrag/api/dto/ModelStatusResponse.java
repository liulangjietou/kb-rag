package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.domain.model.ModelStatus;

/**
 * Model configuration view consumed by the console to grey out model backed controls.
 *
 * @param embeddingConfigured {@code false} means zero key mode, BM25 single route retrieval
 * @param vectorEngine        configured vector engine, {@code es} or {@code milvus}
 * @param provider            embedding provider name, {@code none} when unconfigured
 * @param model               embedding model name, {@code none} when unconfigured
 * @param dimension           vector dimension, 0 when unconfigured
 */
public record ModelStatusResponse(
        @JsonProperty("embedding_configured") boolean embeddingConfigured,
        @JsonProperty("vector_engine") String vectorEngine,
        String provider,
        String model,
        int dimension) {

    /**
     * Maps a domain snapshot onto the transport shape.
     *
     * @param status domain snapshot
     * @return transport response
     */
    public static ModelStatusResponse from(ModelStatus status) {
        return new ModelStatusResponse(
                status.isEmbeddingConfigured(),
                status.getVectorEngine(),
                status.getProvider(),
                status.getModel(),
                status.getDimension());
    }
}
