package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import io.kbrag.app.memory.MemoryAppKeyService;

/**
 * Response of a memory key issue or rotation: the only place the plaintext ever appears.
 *
 * <p>Same stance as {@link ApiKeyCreatedResponse}: a distinct shape keeps the secret out of every
 * list endpoint by construction, and the contract's {@code allOf} flattening is realised with
 * {@code @JsonUnwrapped}.
 *
 * @author owlzhangfq@gmail.com
 */
public record MemoryAppKeyIssuedResponse(

        @JsonUnwrapped
        MemoryAppKeyResponse key,

        @JsonProperty("api_key")
        String apiKey) {

    /**
     * Maps a freshly issued key onto the response carrying its plaintext.
     *
     * @param issued issued key
     * @return response body
     */
    public static MemoryAppKeyIssuedResponse from(MemoryAppKeyService.IssuedKey issued) {
        return new MemoryAppKeyIssuedResponse(MemoryAppKeyResponse.from(issued.key()),
                issued.plaintext());
    }
}
