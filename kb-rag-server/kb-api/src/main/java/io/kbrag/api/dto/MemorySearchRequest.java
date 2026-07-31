package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.app.memory.MemorySearchCommand;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * SearchMemory payload of the memory open API, the M19 contract.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
public class MemorySearchRequest {

    /** Contract default of {@code max_results}. */
    private static final int DEFAULT_MAX_RESULTS = 10;

    /** Memory entity id. */
    @JsonProperty("user_id")
    @NotBlank(message = "user_id must not be blank")
    private String userId;

    /** Current question. */
    @NotBlank(message = "query must not be blank")
    @Size(max = 2000, message = "query must be at most 2000 characters")
    private String query;

    /** Restricts recall to one fragment rule. */
    @JsonProperty("fragment_rule_id")
    private String fragmentRuleId;

    /** Most nodes returned, contract range 1 to 100, default 10. */
    @JsonProperty("max_results")
    @Min(value = 1, message = "max_results must be at least 1")
    @Max(value = 100, message = "max_results must be at most 100")
    private Integer maxResults;

    /** Lets the model veto the recall for questions that need no memory. */
    @JsonProperty("intent_recognition")
    private boolean intentRecognition;

    /** Rewrites the colloquial question before recall. */
    private boolean rewrite;

    /** Reranks the candidates with the rerank model. */
    private boolean rerank;

    /** Drops reranked results below it; only honoured when rerank is on. */
    @JsonProperty("similarity_threshold")
    @DecimalMin(value = "0.0", message = "similarity_threshold must be at least 0.0")
    @DecimalMax(value = "1.0", message = "similarity_threshold must be at most 1.0")
    private Double similarityThreshold;

    /**
     * Maps the transport shape onto the application command.
     *
     * @return application command
     */
    public MemorySearchCommand toCommand() {
        return new MemorySearchCommand(userId, query, fragmentRuleId,
                maxResults == null ? DEFAULT_MAX_RESULTS : maxResults,
                intentRecognition, rewrite, rerank, similarityThreshold);
    }
}
