package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.app.eval.EvalDatasetService;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.entity.EvalDataset;
import io.kbrag.domain.entity.EvalRun;
import io.kbrag.domain.model.EvalRetrievalConfig;

/**
 * Evaluation data set list and detail payload.
 *
 * @param datasetId       business identifier
 * @param kbId            owning knowledge base
 * @param name            display name
 * @param description     free text description
 * @param datasetRevision current revision, bumped on any case mutation
 * @param caseCount       non deprecated case count
 * @param lastRun         most recent run summary, {@code null} when none was executed yet
 * @param createdAt       ISO creation timestamp
 *
 * @author owlzhangfq@gmail.com
 */
public record EvalDatasetResponse(
        @JsonProperty("dataset_id") String datasetId,
        @JsonProperty("kb_id") String kbId,
        String name,
        String description,
        @JsonProperty("dataset_revision") Integer datasetRevision,
        @JsonProperty("case_count") Integer caseCount,
        // Always serialised, even when null: the console tells "no run yet" from "run field omitted"
        // only if the key is always present.
        @JsonInclude(JsonInclude.Include.ALWAYS) @JsonProperty("last_run") LastRunSummary lastRun,
        @JsonProperty("created_at") String createdAt) {

    /**
     * Maps a view onto its response.
     *
     * @param view data set together with its latest run
     * @return response
     */
    public static EvalDatasetResponse from(EvalDatasetService.DatasetView view) {
        EvalDataset dataset = view.dataset();
        return new EvalDatasetResponse(
                dataset.getDatasetId(),
                dataset.getKbId(),
                dataset.getName(),
                dataset.getDescription(),
                dataset.getDatasetRevision(),
                dataset.getCaseCount(),
                LastRunSummary.from(view.lastRun()),
                dataset.getCreatedAt() == null ? null : dataset.getCreatedAt().toString());
    }

    /**
     * Fixed shape of the {@code last_run} field: {@code run_id}, {@code status}, {@code mode},
     * {@code finished_at}.
     *
     * @param runId      run business id
     * @param status     run lifecycle state
     * @param mode       retrieval mode the run's configuration used
     * @param finishedAt ISO completion timestamp, {@code null} while the run has not finished
     */
    public record LastRunSummary(
            @JsonProperty("run_id") String runId,
            String status,
            String mode,
            @JsonProperty("finished_at") String finishedAt) {

        private static LastRunSummary from(EvalRun run) {
            if (run == null) {
                return null;
            }
            EvalRetrievalConfig config = JsonUtil.parse(run.getRetrievalConfig(), EvalRetrievalConfig.class);
            return new LastRunSummary(
                    run.getRunId(),
                    run.getStatus() == null ? null : run.getStatus().name(),
                    config == null || config.getMode() == null ? null : config.getMode().name(),
                    run.getFinishedAt() == null ? null : run.getFinishedAt().toString());
        }
    }
}
