package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * Payload of {@code POST /api/v1/retrieval-feedback/{feedbackId}/convert}: the one thing a conversion
 * needs that the feedback row does not already know is where the case should land.
 *
 * @param datasetId target evaluation data set
 *
 * @author owlzhangfq@gmail.com
 */
public record ConvertFeedbackRequest(
        @JsonProperty("dataset_id") @NotBlank(message = "must not be blank") String datasetId) {
}
