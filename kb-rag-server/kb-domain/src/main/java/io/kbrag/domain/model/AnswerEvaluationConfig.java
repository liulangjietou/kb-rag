package io.kbrag.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Frozen input of a final-answer evaluation run.
 *
 * <p>The complete application snapshot is stored on the run before it enters an executor. A later edit,
 * release or supersede transition therefore cannot change which prompt and generation model the report
 * measured, and the worker does not have to recover request-thread tenant context to reload a subordinate
 * application version.
 *
 * @param appVersionId application version selected for generation
 * @param snapshot     complete application configuration frozen at submission time
 *
 * @author owlzhangfq@gmail.com
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnswerEvaluationConfig(
        @JsonProperty("app_version_id") String appVersionId,
        AppConfigSnapshot snapshot) {
}
