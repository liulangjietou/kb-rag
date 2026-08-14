package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload of {@code PUT /api/v1/ext-sources/{sourceId}}: updates the connection details of one
 * source, the M14 contract section 2.3.
 *
 * <p>The secret is the one optional credential: the read API always masks it, so an edit form has
 * nothing real to echo back - a blank value means "keep the stored secret".
 *
 * @param name        operator facing display name, unique per knowledge base
 * @param endpoint    remote service endpoint
 * @param region      optional connector-specific region hint
 * @param bucket      bucket name or Confluence space key
 * @param prefix      optional connector-specific listing prefix
 * @param accessKey   access key or Atlassian account email
 * @param secretKey   new secret credential, blank or absent keeps the stored one
 * @param syncEnabled whether the scheduled pass includes this source, absent keeps current
 *
 * @author owlzhangfq@gmail.com
 */
public record UpdateExtSourceRequest(
        @NotBlank(message = "must not be blank") @Size(max = 128, message = "must be at most 128 characters")
        String name,
        @NotBlank(message = "must not be blank") @Size(max = 512, message = "must be at most 512 characters")
        String endpoint,
        @Size(max = 64, message = "must be at most 64 characters")
        String region,
        @NotBlank(message = "must not be blank") @Size(max = 128, message = "must be at most 128 characters")
        String bucket,
        @Size(max = 512, message = "must be at most 512 characters")
        String prefix,
        @JsonProperty("access_key")
        @NotBlank(message = "must not be blank") @Size(max = 256, message = "must be at most 256 characters")
        String accessKey,
        @JsonProperty("secret_key")
        @Size(max = 512, message = "must be at most 512 characters")
        String secretKey,
        @JsonProperty("sync_enabled") Boolean syncEnabled) {
}
