package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload of {@code POST /api/v1/kb/{kbId}/ext-sources}: registers one external object store
 * source, the M14 contract section 2.3.
 *
 * @param sourceType  connector type routing key, {@code s3} in this milestone
 * @param name        operator facing display name, unique per knowledge base
 * @param endpoint    service endpoint of the object store
 * @param region      optional region hint of the object store
 * @param bucket      bucket the scan lists
 * @param prefix      optional key prefix narrowing the scan
 * @param accessKey   access key of the bucket credentials
 * @param secretKey   secret key of the bucket credentials
 * @param syncEnabled whether the scheduled pass should keep this source fresh, defaults to on
 *
 * @author owlzhangfq@gmail.com
 */
public record RegisterExtSourceRequest(
        @JsonProperty("source_type")
        @NotBlank(message = "must not be blank") @Size(max = 16, message = "must be at most 16 characters")
        String sourceType,
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
        @NotBlank(message = "must not be blank") @Size(max = 512, message = "must be at most 512 characters")
        String secretKey,
        @JsonProperty("sync_enabled") Boolean syncEnabled) {
}
