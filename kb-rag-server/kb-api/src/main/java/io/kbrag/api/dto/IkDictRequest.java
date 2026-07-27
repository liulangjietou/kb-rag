package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.enums.DictType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * New dictionary entry.
 *
 * @param word     dictionary term
 * @param dictType dictionary kind, {@code EXT} or {@code STOP}
 * @param remark   free text note explaining why the term was added
 *
 * @author owlzhangfq@gmail.com
 */
public record IkDictRequest(
        @NotBlank(message = "must not be blank")
        @Size(max = 64, message = "must be at most 64 characters") String word,
        @JsonProperty("dict_type") @NotBlank(message = "must not be blank") String dictType,
        @Size(max = 512, message = "must be at most 512 characters") String remark) {

    /**
     * Resolves the dictionary kind, failing fast on an unknown literal.
     *
     * @return dictionary kind
     */
    public DictType resolvedType() {
        DictType resolved = DictType.from(dictType);
        if (resolved == null) {
            throw BizException.invalidParam("unknown dict_type: " + dictType);
        }
        return resolved;
    }
}
