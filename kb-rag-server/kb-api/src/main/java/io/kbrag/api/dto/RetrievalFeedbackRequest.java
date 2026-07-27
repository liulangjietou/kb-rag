package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * Payload of {@code POST /api/v1/retrieval-feedback}, requirement section 4.5 "good/bad feedback on a
 * debug result".
 *
 * <p>Deliberately leads nowhere by itself (see the M4b contract section 2): the requirement asks that
 * feedback be "distilled into evaluation material", and a {@code GOOD} verdict without a target data
 * set has nowhere to land - the actual collection path is
 * {@code POST /eval-datasets/{datasetId}/cases/from-retrieval}, which the debug page calls once an
 * operator picks a data set. This endpoint therefore only records the signal for now; a future
 * milestone can wire it to a data set picker without changing this payload.
 *
 * @param kbId    knowledge base the query ran against
 * @param query   query the debug page ran
 * @param chunkId chunk the feedback concerns
 * @param verdict {@code GOOD} or {@code BAD}
 *
 * @author owlzhangfq@gmail.com
 */
public record RetrievalFeedbackRequest(
        @JsonProperty("kb_id") @NotBlank(message = "must not be blank") String kbId,
        @NotBlank(message = "must not be blank") String query,
        @JsonProperty("chunk_id") @NotBlank(message = "must not be blank") String chunkId,
        @NotBlank(message = "must not be blank") String verdict) {
}
