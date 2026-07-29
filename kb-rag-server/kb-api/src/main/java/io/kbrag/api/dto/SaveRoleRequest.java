package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Role definition submitted by the console, used for both creation and update.
 *
 * <p>The grants and the scope are complete sets, not deltas, because that is what the checkbox grid in front
 * of the operator represents. The code is only read on creation; on update it is ignored, since configuration
 * and the provisioning default refer to it.
 *
 * @param code            role code, uppercased, required on creation
 * @param name            display label
 * @param description     purpose note
 * @param kbScopeAll      whether the role sees every knowledge base
 * @param kbIds           scoped knowledge bases, ignored when {@code kbScopeAll}
 * @param permissionCodes complete set of granted permission codes
 *
 * @author owlzhangfq@gmail.com
 */
public record SaveRoleRequest(
        @Size(max = 64, message = "must be at most 64 characters") String code,
        @NotBlank(message = "must not be blank")
        @Size(max = 64, message = "must be at most 64 characters")
        String name,
        @Size(max = 255, message = "must be at most 255 characters") String description,
        @JsonProperty("kb_scope_all") boolean kbScopeAll,
        @JsonProperty("kb_ids") List<String> kbIds,
        @JsonProperty("permission_codes") List<String> permissionCodes) {
}
