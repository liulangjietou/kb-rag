package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.app.openapi.ApiKeyService;

import java.util.List;

/**
 * Response of a key creation or rotation: the only place the plaintext ever appears.
 *
 * <p>Separate from {@link ApiKeyResponse} on purpose. Two shapes make it impossible to leak the secret by
 * accident from a list endpoint, and they make the "shown once" rule visible in the type system rather than
 * living in a comment.
 *
 * @param key    the stored key, without any secret material
 * @param apiKey plaintext key; shown once, never recoverable afterwards
 *
 * @author owlzhangfq@gmail.com
 */
public record ApiKeyCreatedResponse(
        ApiKeyResponse key,
        @JsonProperty("api_key") String apiKey) {

    /**
     * Maps a freshly issued key onto its response.
     *
     * @param issued   issued key
     * @param appScope parsed authorisation scope
     * @return response carrying the plaintext
     */
    public static ApiKeyCreatedResponse from(ApiKeyService.IssuedKey issued, List<String> appScope) {
        return new ApiKeyCreatedResponse(ApiKeyResponse.from(issued.key(), appScope), issued.plaintext());
    }
}
