package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.app.eval.EvalDemoImportService;

import java.util.List;

/**
 * Response of {@code POST /api/v1/kb/{kbId}/eval-datasets/import-demo}.
 *
 * @param datasetId         data set business id, freshly created or the one idempotency returned
 * @param alreadyExisted    {@code true} when an earlier run already created this data set
 * @param importedCaseCount cases actually created
 * @param skipped           cases whose evidence could not be resolved
 *
 * @author owlzhangfq@gmail.com
 */
public record ImportDemoEvalDatasetResponse(
        @JsonProperty("dataset_id") String datasetId,
        @JsonProperty("already_existed") boolean alreadyExisted,
        @JsonProperty("imported_case_count") int importedCaseCount,
        List<SkippedView> skipped) {

    /**
     * Maps a service outcome onto its response.
     *
     * @param result import outcome
     * @return response
     */
    public static ImportDemoEvalDatasetResponse from(EvalDemoImportService.ImportResult result) {
        return new ImportDemoEvalDatasetResponse(
                result.datasetId(),
                result.alreadyExisted(),
                result.importedCaseCount(),
                result.skipped().stream().map(SkippedView::from).toList());
    }

    /**
     * One demo case that was not imported.
     *
     * @param caseIndex zero based position inside the manifest's {@code cases} array
     * @param reason    why it was skipped
     */
    public record SkippedView(@JsonProperty("case_index") int caseIndex, String reason) {

        private static SkippedView from(EvalDemoImportService.SkippedCase skipped) {
            return new SkippedView(skipped.caseIndex(), skipped.reason());
        }
    }
}
