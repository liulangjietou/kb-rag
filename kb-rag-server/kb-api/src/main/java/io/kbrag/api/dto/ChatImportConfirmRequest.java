package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Body of a chat import confirmation.
 *
 * @param uploadToken token returned by the match preview
 * @param sessionIds  conversations to import, absent or empty imports every conversation of the export
 *
 * @author owlzhangfq@gmail.com
 */
public record ChatImportConfirmRequest(
        @JsonProperty("upload_token") @NotBlank String uploadToken,
        @JsonProperty("session_ids") List<String> sessionIds) {
}
