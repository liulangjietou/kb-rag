package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.domain.entity.SourceMapping;

/**
 * Chat import mapping profile view.
 *
 * <p>The YAML body travels in the list response as well as in the single row responses: the console shows
 * the built-in template in the same text area an operator edits a copy in, so withholding it from the list
 * would only buy a second round trip per row.
 *
 * @param mappingId   business identifier
 * @param name        profile name, also accepted as the {@code mapping_profile} import parameter
 * @param sourceType  export format, {@code csv}, {@code xlsx}, {@code txt} or {@code html}
 * @param profileYaml full YAML body forwarded to the parser
 * @param isBuiltin   {@code true} for a seeded template, which can be copied but neither edited nor deleted
 * @param createdAt   ISO creation timestamp
 * @param updatedAt   ISO update timestamp
 *
 * @author owlzhangfq@gmail.com
 */
public record SourceMappingResponse(
        @JsonProperty("mapping_id") String mappingId,
        String name,
        @JsonProperty("source_type") String sourceType,
        @JsonProperty("profile_yaml") String profileYaml,
        @JsonProperty("is_builtin") boolean isBuiltin,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt) {

    /**
     * Maps an entity onto its view.
     *
     * @param entity mapping profile row
     * @return view
     */
    public static SourceMappingResponse from(SourceMapping entity) {
        return new SourceMappingResponse(
                entity.getMappingId(),
                entity.getName(),
                entity.getSourceType() == null ? null : entity.getSourceType().code(),
                entity.getProfileYaml(),
                entity.builtin(),
                entity.getCreatedAt() == null ? null : entity.getCreatedAt().toString(),
                entity.getUpdatedAt() == null ? null : entity.getUpdatedAt().toString());
    }
}
