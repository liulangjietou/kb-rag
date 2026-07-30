package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.app.document.DocumentAclService;

import java.util.List;

/**
 * Visibility of one document together with its granted roles.
 *
 * @param visibility INHERIT or RESTRICTED
 * @param roleIds    granted role ids, always present and empty unless RESTRICTED
 *
 * @author owlzhangfq@gmail.com
 */
public record DocumentVisibilityResponse(
        String visibility,
        @JsonProperty("role_ids") List<String> roleIds) {

    /**
     * Maps the service view onto the wire shape.
     *
     * @param view service side view
     * @return response
     */
    public static DocumentVisibilityResponse from(DocumentAclService.VisibilityView view) {
        return new DocumentVisibilityResponse(view.visibility().name(), view.roleIds());
    }
}
