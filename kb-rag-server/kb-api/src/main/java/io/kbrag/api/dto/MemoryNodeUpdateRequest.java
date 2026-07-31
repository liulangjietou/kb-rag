package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * UpdateMemory payload of the memory open API, the M19 contract.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
public class MemoryNodeUpdateRequest {

    /** Memory entity id the node must belong to; a mismatch answers 404. */
    @JsonProperty("user_id")
    @NotBlank(message = "user_id must not be blank")
    private String userId;

    /** New content, replaces the stored one and is re-vectorised. */
    @JsonProperty("custom_content")
    @NotBlank(message = "custom_content must not be blank")
    @Size(max = 4000, message = "custom_content must be at most 4000 characters")
    private String customContent;

    /** New metadata; absent keeps the stored one, an empty object clears it. */
    @JsonProperty("meta_data")
    private Map<String, Object> metaData;
}
