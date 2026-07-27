package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.enums.SourceMappingType;
import jakarta.validation.constraints.NotBlank;

/**
 * Creation and full replacement payload of a chat import mapping profile.
 *
 * <p>The same record serves both verbs because the update is a full replacement rather than a patch: the
 * console edits the YAML in a text area and posts the whole row back, and a payload whose fields were all
 * optional would let a name, a format and a body that describe different exports coexist in one row.
 *
 * @param name        profile name, unique across built-in and custom rows
 * @param sourceType  export format literal, {@code csv}, {@code xlsx}, {@code txt} or {@code html}
 * @param profileYaml full YAML body forwarded to the parser
 *
 * @author owlzhangfq@gmail.com
 */
public record SourceMappingRequest(
        @NotBlank String name,
        @JsonProperty("source_type") @NotBlank String sourceType,
        @JsonProperty("profile_yaml") @NotBlank String profileYaml) {

    /**
     * Resolves the export format literal.
     *
     * <p>The fast-fail gate of this payload: an unknown format would otherwise be stored and only be
     * discovered by the import that could not match it against an uploaded file.
     *
     * @return export format
     */
    public SourceMappingType resolvedType() {
        SourceMappingType resolved = SourceMappingType.from(sourceType);
        if (resolved == null) {
            throw BizException.invalidParam("unknown source_type: " + sourceType);
        }
        return resolved;
    }
}
