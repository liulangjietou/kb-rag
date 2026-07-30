package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.domain.entity.RetrievalFeedback;

/**
 * Acknowledgement of an open API feedback, the M16 contract section 7.
 *
 * <p>Only the business id is returned: the row's resolved knowledge base and digest are console
 * concerns, and echoing them here would leak what the correlation id maps to.
 *
 * @author owlzhangfq@gmail.com
 */
public record KnowledgeFeedbackResponse(

        @JsonProperty("feedback_id")
        String feedbackId) {

    /**
     * Maps the persisted row onto the transport shape.
     *
     * @param feedback persisted row
     * @return response body
     */
    public static KnowledgeFeedbackResponse from(RetrievalFeedback feedback) {
        return new KnowledgeFeedbackResponse(feedback.getFeedbackId());
    }
}
