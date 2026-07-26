package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.app.graph.GraphSummaryView;
import io.kbrag.domain.entity.KbTask;

/**
 * Knowledge graph overview of one knowledge base, requirement section 4.9.
 *
 * @param graphEnabled       knowledge base level graph switch
 * @param entityCount        distinct entities
 * @param relationCount      relations between them
 * @param coveredChunkCount  chunks at least one entity traces back to
 * @param latestTask         most recent extraction task, {@code null} when none ever ran
 *
 * @author owlzhangfq@gmail.com
 */
public record GraphSummaryResponse(
        @JsonProperty("graph_enabled") boolean graphEnabled,
        @JsonProperty("entity_count") long entityCount,
        @JsonProperty("relation_count") long relationCount,
        @JsonProperty("covered_chunk_count") long coveredChunkCount,
        @JsonProperty("latest_task") GraphTask latestTask) {

    /**
     * State of one graph extraction run.
     *
     * <p>{@code skippedChunkCount} is reported on a successful task as well, and that is the point of it:
     * an extraction that succeeded while its output validation rejected a third of the corpus looks
     * exactly like a clean one without this number.
     *
     * @param taskId            task business id
     * @param type              task category, always {@code GRAPH_EXTRACT}
     * @param status            lifecycle state
     * @param progress          completion percentage
     * @param skippedChunkCount chunks dropped by the output validation, {@code null} before the first run
     * @param errorMessage      classified failure cause, {@code null} unless the task failed
     * @param createdAt         ISO creation timestamp
     */
    public record GraphTask(
            @JsonProperty("task_id") String taskId,
            String type,
            String status,
            Integer progress,
            @JsonProperty("skipped_chunk_count") Integer skippedChunkCount,
            @JsonProperty("error_message") String errorMessage,
            @JsonProperty("created_at") String createdAt) {
    }

    /**
     * Maps an application view onto the transport shape.
     *
     * @param view application view
     * @return transport summary
     */
    public static GraphSummaryResponse from(GraphSummaryView view) {
        return new GraphSummaryResponse(view.graphEnabled(),
                view.counts().entityCount(),
                view.counts().relationCount(),
                view.counts().coveredChunkCount(),
                taskOf(view.latestTask()));
    }

    private static GraphTask taskOf(KbTask task) {
        if (task == null) {
            return null;
        }
        return new GraphTask(task.getTaskId(),
                task.getTaskType() == null ? null : task.getTaskType().name(),
                task.getStatus() == null ? null : task.getStatus().name(),
                task.getProgress(),
                task.getSkippedCount(),
                task.getFailReason(),
                task.getCreatedAt() == null ? null : task.getCreatedAt().toString());
    }
}
