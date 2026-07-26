package io.kbrag.api.controller;

import io.kbrag.api.dto.CreateEvalDatasetRequest;
import io.kbrag.api.dto.EvalDatasetResponse;
import io.kbrag.api.dto.ImportDemoEvalDatasetResponse;
import io.kbrag.app.eval.EvalDatasetService;
import io.kbrag.app.eval.EvalDemoImportService;
import io.kbrag.common.api.Result;
import io.kbrag.domain.entity.EvalDataset;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Evaluation data set management endpoints, requirement section 4.5.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class EvalDatasetController {

    private final EvalDatasetService evalDatasetService;
    private final EvalDemoImportService evalDemoImportService;

    /**
     * Creates an evaluation data set.
     *
     * @param kbId    owning knowledge base business id
     * @param request name and description
     * @return created data set
     */
    @PostMapping("/api/v1/kb/{kbId}/eval-datasets")
    public Result<EvalDatasetResponse> create(@PathVariable String kbId,
                                              @Valid @RequestBody CreateEvalDatasetRequest request) {
        EvalDataset dataset = evalDatasetService.create(kbId, request.name(), request.description());
        return Result.success(EvalDatasetResponse.from(evalDatasetService.detail(dataset.getDatasetId())));
    }

    /**
     * Lists the data sets of a knowledge base.
     *
     * @param kbId knowledge base business id
     * @return data sets, newest first, each with its latest run summary
     */
    @GetMapping("/api/v1/kb/{kbId}/eval-datasets")
    public Result<List<EvalDatasetResponse>> list(@PathVariable String kbId) {
        return Result.success(evalDatasetService.list(kbId).stream().map(EvalDatasetResponse::from).toList());
    }

    /**
     * Imports the bundled demo evaluation case set into a knowledge base, requirement section 5.
     *
     * @param kbId knowledge base the demo documents were imported into
     * @return import outcome, idempotent by data set name
     */
    @PostMapping("/api/v1/kb/{kbId}/eval-datasets/import-demo")
    public Result<ImportDemoEvalDatasetResponse> importDemo(@PathVariable String kbId) {
        return Result.success(ImportDemoEvalDatasetResponse.from(evalDemoImportService.importDemo(kbId)));
    }

    /**
     * Loads a data set's detail.
     *
     * @param datasetId data set business id
     * @return detail together with its latest run summary
     */
    @GetMapping("/api/v1/eval-datasets/{datasetId}")
    public Result<EvalDatasetResponse> detail(@PathVariable String datasetId) {
        return Result.success(EvalDatasetResponse.from(evalDatasetService.detail(datasetId)));
    }

    /**
     * Deletes a data set together with every case, run and result it owns.
     *
     * @param datasetId data set business id
     * @return empty payload
     */
    @DeleteMapping("/api/v1/eval-datasets/{datasetId}")
    public Result<Void> delete(@PathVariable String datasetId) {
        evalDatasetService.delete(datasetId);
        return Result.success(null);
    }
}
