package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Request to replace the visibility of a document and its complete grant set.
 *
 * @param visibility INHERIT or RESTRICTED
 * @param roleIds    granted role ids, must be empty for INHERIT and non empty for RESTRICTED
 *
 * @author owlzhangfq@gmail.com
 */
public record UpdateDocumentVisibilityRequest(
        @NotBlank(message = "visibility 不能为空") String visibility,
        @JsonProperty("role_ids") List<String> roleIds) {
}
