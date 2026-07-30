package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * End user feedback payload of the open API, the M16 contract section 7.
 *
 * <p>The caller names a {@code request_id} instead of a knowledge base: the correlation id of the
 * retrieval that showed the chunk is the proof that the verdict is about something this caller
 * actually saw, and the knowledge base is resolved server side from it. {@code end_user_id} is
 * caller asserted and stored as such - the platform authenticates the API key, not the human
 * behind the integrating application.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
public class KnowledgeFeedbackRequest {

    /** Correlation id of the retrieval the verdict concerns, echoed by the search and chat responses. */
    @NotBlank(message = "request_id must not be blank")
    @JsonProperty("request_id")
    private String requestId;

    /** Chunk the verdict concerns. */
    @NotBlank(message = "chunk_id must not be blank")
    @JsonProperty("chunk_id")
    private String chunkId;

    /** End user verdict, {@code GOOD} or {@code BAD}. */
    @NotBlank(message = "verdict must not be blank")
    private String verdict;

    /** Free form comment, optional, truncated server side to the stored width. */
    private String comment;

    /** Caller asserted end user identifier, optional, not vouched for by the platform. */
    @JsonProperty("end_user_id")
    private String endUserId;
}
