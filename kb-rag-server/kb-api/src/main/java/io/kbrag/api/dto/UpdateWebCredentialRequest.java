package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * PUT /api/v1/web-credentials/{credentialId} request body, the M18 contract.
 *
 * <p>Everything is optional and only the present fields are written. The secret in particular:
 * absent or blank keeps the stored one, so the console can flip the enabled switch or fix a
 * username without ever holding the password. The host and the auth type are deliberately not
 * updatable - a credential that changes site or scheme is a different credential; delete and
 * recreate says so explicitly.
 *
 * @param username   new BASIC username, absent keeps
 * @param secret     new secret, absent or blank keeps the stored one
 * @param headerName new HEADER header name, absent keeps
 * @param enabled    new switch value, absent keeps
 *
 * @author owlzhangfq@gmail.com
 */
public record UpdateWebCredentialRequest(
        String username,
        String secret,
        @JsonProperty("header_name") String headerName,
        Boolean enabled) {
}
