package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/**
 * Editable fields of an account.
 *
 * <p>The login name is absent on purpose: session tokens and every audit row key on it, so renaming would
 * detach the account from its own history.
 *
 * @param displayName new display label
 * @param email       new contact address
 *
 * @author owlzhangfq@gmail.com
 */
public record UpdateUserRequest(
        @JsonProperty("display_name")
        @Size(max = 64, message = "must be at most 64 characters")
        String displayName,
        @Email(message = "must be a valid address")
        @Size(max = 128, message = "must be at most 128 characters")
        String email) {
}
