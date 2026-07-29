package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.domain.entity.AdminUser;
import io.kbrag.domain.model.UserPrincipal;

import java.util.List;

/**
 * Authenticated account view. The password hash is never part of it.
 *
 * <p>The permission codes travel with it so the console can hide the navigation entries and the buttons a
 * caller cannot use. That is presentation only: hiding a button is not a control, and every one of these
 * codes is checked again on the server when the call arrives.
 *
 * @param username           login name
 * @param displayName        display label
 * @param source             where the account came from, {@code LOCAL} or {@code LDAP}
 * @param mustChangePassword {@code true} while the initial password has not been rotated
 * @param lastLoginAt        ISO timestamp of the previous successful login, {@code null} on first login
 * @param roles              role codes held
 * @param permissions        flattened permission codes
 * @param kbScopeAll         {@code true} when the caller sees every knowledge base
 * @param kbIds              knowledge bases in scope, meaningful only when {@code kbScopeAll} is false
 *
 * @author owlzhangfq@gmail.com
 */
public record MeResponse(
        String username,
        @JsonProperty("display_name") String displayName,
        String source,
        @JsonProperty("must_change_password") boolean mustChangePassword,
        @JsonProperty("last_login_at") String lastLoginAt,
        List<String> roles,
        List<String> permissions,
        @JsonProperty("kb_scope_all") boolean kbScopeAll,
        @JsonProperty("kb_ids") List<String> kbIds) {

    /**
     * Builds the view from the stored account and the resolved permissions of the session.
     *
     * @param user      account record
     * @param principal flattened permissions bound to the request
     * @return account view
     */
    public static MeResponse from(AdminUser user, UserPrincipal principal) {
        return new MeResponse(
                user.getUsername(),
                principal.displayName(),
                user.getSource() == null ? null : user.getSource().name(),
                user.mustChangePassword(),
                user.getLastLoginAt() == null ? null : user.getLastLoginAt().toString(),
                List.copyOf(principal.roleCodes()),
                List.copyOf(principal.permissions()),
                principal.kbScopeAll(),
                List.copyOf(principal.kbIds()));
    }
}
