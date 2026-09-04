package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Payload of the account an operator creates by hand.
 *
 * <p>The initial password is chosen by the operator and has to be rotated at first login, so the person who
 * typed it does not stay able to sign in as the account they created.
 *
 * @param username    login name, unique across local and directory accounts
 * @param displayName display label, falls back to the login name when blank
 * @param email       contact address, optional
 * @param password    initial password
 * @param roleIds     roles granted right away, may be empty
 * @param tenantId    owning tenant, only honoured for a caller holding {@code tenant:manage}
 *
 * @author owlzhangfq@gmail.com
 */
public record CreateUserRequest(
        @NotBlank(message = "must not be blank")
        @Size(max = 64, message = "must be at most 64 characters")
        String username,
        @JsonProperty("display_name")
        @Size(max = 64, message = "must be at most 64 characters")
        String displayName,
        @Email(message = "must be a valid address")
        @Size(max = 254, message = "must be at most 254 characters")
        String email,
        @NotBlank(message = "must not be blank")
        @Size(min = 8, max = 64, message = "must be between 8 and 64 characters")
        String password,
        @JsonProperty("role_ids") List<String> roleIds,
        @JsonProperty("tenant_id")
        @Size(max = 64, message = "must be at most 64 characters")
        String tenantId) {
}
