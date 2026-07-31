package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.domain.entity.WebCredential;

/**
 * Site credential view, the M18 contract.
 *
 * <p><b>The secret has no field here</b>, which is what "the read API never returns it" means
 * mechanically: not an omitted value but an impossible one. The console proves a secret exists by
 * the row existing, and replaces it by writing a new one.
 *
 * @param credentialId business identifier
 * @param host         exact host the credential applies to
 * @param authType     BASIC or HEADER
 * @param username     BASIC username, {@code null} for HEADER
 * @param headerName   HEADER header name, {@code null} for BASIC
 * @param enabled      whether fetches may use it
 * @param createdAt    ISO creation timestamp
 *
 * @author owlzhangfq@gmail.com
 */
public record WebCredentialResponse(
        @JsonProperty("credential_id") String credentialId,
        String host,
        @JsonProperty("auth_type") String authType,
        String username,
        @JsonProperty("header_name") String headerName,
        boolean enabled,
        @JsonProperty("created_at") String createdAt) {

    private static final int ENABLED = 1;

    /**
     * Maps an entity onto its view.
     *
     * @param entity credential row
     * @return view without the secret
     */
    public static WebCredentialResponse from(WebCredential entity) {
        return new WebCredentialResponse(
                entity.getCredentialId(),
                entity.getHost(),
                entity.getAuthType() == null ? null : entity.getAuthType().name(),
                entity.getUsername(),
                entity.getHeaderName(),
                entity.getEnabled() != null && entity.getEnabled() == ENABLED,
                entity.getCreatedAt() == null ? null : entity.getCreatedAt().toString());
    }
}
