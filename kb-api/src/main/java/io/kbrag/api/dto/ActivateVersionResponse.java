package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.app.document.DocumentVersionService;

/**
 * Answer of a version switch, identical in shape for both modes.
 *
 * <p>{@code task_id} is {@code null} for an instant switch, which has already completed by the time the
 * response is written, and carries the rebuild task otherwise. The field is serialised even when null -
 * hence the explicit inclusion override against the service wide non-null default - because a console
 * that has to distinguish "no task needed" from "field missing" would otherwise be guessing.
 *
 * @param taskId       rebuild task business id, {@code null} when the switch already happened
 * @param rollbackMode mode that was applied
 *
 * @author owlzhangfq@gmail.com
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ActivateVersionResponse(
        @JsonProperty("task_id") String taskId,
        @JsonProperty("rollback_mode") String rollbackMode) {

    /**
     * Maps an outcome onto its response.
     *
     * @param activation activation outcome
     * @return response
     */
    public static ActivateVersionResponse from(DocumentVersionService.Activation activation) {
        return new ActivateVersionResponse(activation.taskId(), activation.rollbackMode().name());
    }
}
