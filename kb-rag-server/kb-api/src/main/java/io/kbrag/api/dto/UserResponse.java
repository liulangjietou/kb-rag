package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.domain.entity.AdminUser;

import java.util.List;

/**
 * Console user row. Neither the password hash nor its absence is exposed.
 *
 * @param userId             user business id
 * @param tenantId           owning tenant business id (M16), what the move-tenant console column reads
 * @param username           login name
 * @param displayName        display label
 * @param email              contact address
 * @param source             {@code LOCAL} or {@code LDAP}
 * @param status             {@code ENABLED} or {@code DISABLED}
 * @param mustChangePassword {@code true} while the initial password has not been rotated
 * @param lastLoginAt        ISO timestamp of the last successful login
 * @param createdAt          ISO creation timestamp
 * @param roleIds            role business ids held, filled on the detail view
 * @param roleNames          role display names, filled on the list view
 *
 * @author owlzhangfq@gmail.com
 */
public record UserResponse(
        @JsonProperty("user_id") String userId,
        @JsonProperty("tenant_id") String tenantId,
        String username,
        @JsonProperty("display_name") String displayName,
        String email,
        String source,
        String status,
        @JsonProperty("must_change_password") boolean mustChangePassword,
        @JsonProperty("last_login_at") String lastLoginAt,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("role_ids") List<String> roleIds,
        @JsonProperty("role_names") List<String> roleNames) {

    /**
     * Maps one account onto the transport shape.
     *
     * @param user      account record
     * @param roleIds   role business ids held, may be {@code null}
     * @param roleNames role display names, may be {@code null}
     * @return user row
     */
    public static UserResponse from(AdminUser user, List<String> roleIds, List<String> roleNames) {
        return new UserResponse(
                user.getUserId(),
                user.getTenantId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getEmail(),
                user.getSource() == null ? null : user.getSource().name(),
                user.getStatus() == null ? null : user.getStatus().name(),
                user.mustChangePassword(),
                user.getLastLoginAt() == null ? null : user.getLastLoginAt().toString(),
                user.getCreatedAt() == null ? null : user.getCreatedAt().toString(),
                roleIds == null ? List.of() : roleIds,
                roleNames == null ? List.of() : roleNames);
    }
}
