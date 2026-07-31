package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * POST /api/v1/web-credentials request body, the M18 contract.
 *
 * <p>Only the fields every type shares are annotated: the BASIC/HEADER conditional shape (username
 * versus header name) is one rule owned by the service, not duplicated as bean validation here.
 *
 * @param host       exact host, no scheme, no path; optionally {@code host:port}
 * @param authType   BASIC or HEADER
 * @param username   BASIC username
 * @param secret     BASIC password or the full header value
 * @param headerName HEADER header name, e.g. Authorization or Cookie
 * @param enabled    optional switch, on by default
 *
 * @author owlzhangfq@gmail.com
 */
public record CreateWebCredentialRequest(
        @NotBlank(message = "host 不能为空") String host,
        @JsonProperty("auth_type") @NotBlank(message = "认证类型不能为空") String authType,
        String username,
        @NotBlank(message = "凭据内容不能为空") String secret,
        @JsonProperty("header_name") String headerName,
        Boolean enabled) {
}
