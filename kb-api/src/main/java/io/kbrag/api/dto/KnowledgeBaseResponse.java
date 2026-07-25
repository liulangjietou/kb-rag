package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.domain.entity.KnowledgeBase;

/**
 * Knowledge base view.
 *
 * @param kbId        business identifier
 * @param name        display name
 * @param description free text description
 * @param indexConfig split and embed parameters as raw JSON
 * @param createdAt   ISO creation timestamp
 */
public record KnowledgeBaseResponse(
        @JsonProperty("kb_id") String kbId,
        String name,
        String description,
        @JsonProperty("index_config") String indexConfig,
        @JsonProperty("created_at") String createdAt) {

    /**
     * Maps an entity onto its view.
     *
     * @param entity knowledge base entity
     * @return view
     */
    public static KnowledgeBaseResponse from(KnowledgeBase entity) {
        return new KnowledgeBaseResponse(
                entity.getKbId(),
                entity.getName(),
                entity.getDescription(),
                entity.getIndexConfig(),
                entity.getCreatedAt() == null ? null : entity.getCreatedAt().toString());
    }
}
