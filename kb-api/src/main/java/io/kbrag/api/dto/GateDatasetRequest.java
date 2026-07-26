package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Baseline evaluation data set binding payload.
 *
 * @param datasetId data set to bind; blank or {@code null} clears the binding and skips the gate
 *
 * @author owlzhangfq@gmail.com
 */
public record GateDatasetRequest(@JsonProperty("dataset_id") String datasetId) {
}
