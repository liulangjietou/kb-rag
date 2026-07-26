package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.domain.entity.KnowledgeBase;
import io.kbrag.domain.model.KbIndexConfig;
import io.kbrag.domain.model.KbRetrievalConfig;

/**
 * Knowledge base view.
 *
 * <p>The index configuration is serialised as a structured object rather than as the raw column
 * string: the console binds it straight into the configuration form, and handing over a string would
 * force every caller to parse the column layout for itself. The fingerprint travels with it so the
 * console can tell a form it just submitted from one another operator changed underneath it.
 *
 * @param kbId                    business identifier
 * @param name                    display name
 * @param description             free text description
 * @param indexConfig             split and parent child parameters
 * @param currentConfigFingerprint fingerprint driving the document {@code config_stale} flag
 * @param graphEnabled            graph route switch, inlined for the same reason the index configuration
 *                                is: the console renders it on the knowledge base card and a second
 *                                request per row would be N+1
 * @param createdAt               ISO creation timestamp
 *
 * @author owlzhangfq@gmail.com
 */
public record KnowledgeBaseResponse(
        @JsonProperty("kb_id") String kbId,
        String name,
        String description,
        @JsonProperty("index_config") KbIndexConfig indexConfig,
        @JsonProperty("current_config_fingerprint") String currentConfigFingerprint,
        @JsonProperty("graph_enabled") boolean graphEnabled,
        @JsonProperty("created_at") String createdAt) {

    /**
     * Maps an entity onto its view.
     *
     * @param entity          knowledge base entity
     * @param indexConfig     resolved index configuration
     * @param retrievalConfig resolved retrieval defaults
     * @return view
     */
    public static KnowledgeBaseResponse from(KnowledgeBase entity, KbIndexConfig indexConfig,
                                             KbRetrievalConfig retrievalConfig) {
        return new KnowledgeBaseResponse(
                entity.getKbId(),
                entity.getName(),
                entity.getDescription(),
                indexConfig,
                entity.getCurrentConfigFingerprint(),
                retrievalConfig != null && retrievalConfig.graphEnabled(),
                entity.getCreatedAt() == null ? null : entity.getCreatedAt().toString());
    }
}
