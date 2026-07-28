package io.kbrag.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload of {@code POST /api/v1/documents/{docId}/reject}: a rejection without a reason gives the
 * author nothing to revise, so the note is the one mandatory field.
 *
 * @param note rejection reason shown to the author
 *
 * @author owlzhangfq@gmail.com
 */
public record RejectDocumentRequest(
        @NotBlank(message = "must not be blank") @Size(max = 512, message = "must be at most 512 characters")
        String note) {
}
