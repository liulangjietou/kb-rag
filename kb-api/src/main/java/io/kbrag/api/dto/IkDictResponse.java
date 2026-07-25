package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.domain.entity.IkDict;

/**
 * Dictionary entry view.
 *
 * @param word      dictionary term
 * @param dictType  dictionary kind
 * @param status    availability
 * @param remark    free text note
 * @param createdAt ISO creation timestamp
 * @param updatedAt ISO update timestamp
 *
 * @author owlzhangfq@gmail.com
 */
public record IkDictResponse(
        String word,
        @JsonProperty("dict_type") String dictType,
        String status,
        String remark,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt) {

    /**
     * Maps an entity onto its view.
     *
     * @param entity dictionary entry
     * @return view
     */
    public static IkDictResponse from(IkDict entity) {
        return new IkDictResponse(
                entity.getWord(),
                entity.getDictType() == null ? null : entity.getDictType().name(),
                entity.getStatus() == null ? null : entity.getStatus().name(),
                entity.getRemark(),
                entity.getCreatedAt() == null ? null : entity.getCreatedAt().toString(),
                entity.getUpdatedAt() == null ? null : entity.getUpdatedAt().toString());
    }
}
