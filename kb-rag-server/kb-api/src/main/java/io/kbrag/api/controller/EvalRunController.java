package io.kbrag.api.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.kbrag.api.annotation.AuditedOperation;
import io.kbrag.api.annotation.RequiresPermission;
import io.kbrag.api.dto.EvalRunCompareResponse;
import io.kbrag.api.dto.EvalRunEstimateResponse;
import io.kbrag.api.dto.EvalRunResponse;
import io.kbrag.api.dto.EvalRunSubmitRequest;
import io.kbrag.api.dto.EvalResultResponse;
import io.kbrag.api.dto.PageResponse;
import io.kbrag.app.auth.KbResourceGuard;
import io.kbrag.app.eval.EvalRunService;
import io.kbrag.common.api.Result;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.constant.PermissionCodes;
import io.kbrag.domain.entity.EvalResult;
import io.kbrag.domain.entity.EvalRun;
import io.kbrag.domain.model.AnswerEvaluationConfig;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * Evaluation run submission, report and comparison endpoints, requirement section 4.6.
 *
 * <p>Submitting and estimating are granted by {@code eval:run} rather than by {@code eval:write}: a run
 * spends model quota per case per configuration, so the account allowed to curate a data set is not
 * automatically the one allowed to bill a full sweep of it.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class EvalRunController {

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;

    private final EvalRunService evalRunService;
    private final KbResourceGuard kbResourceGuard;

    /**
     * Submits a configuration matrix, one run created per configuration.
     *
     * @param datasetId data set business id
     * @param request   {@code k}, the configuration matrix and the optional judge switch
     * @return created runs, in submission order
     */
    @PostMapping("/api/v1/eval-datasets/{datasetId}/runs")
    @RequiresPermission(PermissionCodes.EVAL_RUN)
    @AuditedOperation(module = "EVAL", action = "SUBMIT_RUN", targetType = "EVAL_DATASET",
            targetId = "#datasetId")
    public Result<List<EvalRunResponse>> submit(@PathVariable String datasetId,
                                                @Valid @RequestBody EvalRunSubmitRequest request) {
        kbResourceGuard.requireDatasetAccess(datasetId);
        List<EvalRun> runs;
        if (request.answerEnabled()) {
            AnswerEvaluationConfig answerConfig = evalRunService.answerConfig(datasetId,
                    request.answerAppVersionId());
            List<EvalRunService.AnswerRunSpec> specs = request.toConfigs().stream()
                    .map(config -> new EvalRunService.AnswerRunSpec(config, answerConfig))
                    .toList();
            runs = evalRunService.submitAnswerRuns(datasetId, request.k(), specs, request.judgeEnabled());
        } else {
            runs = evalRunService.submit(datasetId, request.k(), request.toConfigs(), request.judgeEnabled());
        }
        return Result.success(runs.stream().map(EvalRunResponse::from).toList());
    }

    /**
     * Predicts the model calls a submission would issue, before it is actually submitted.
     *
     * @param datasetId data set business id
     * @param request   same shape as the submission payload
     * @return predicted call counts by capability
     */
    @PostMapping("/api/v1/eval-datasets/{datasetId}/runs/estimate")
    @RequiresPermission(PermissionCodes.EVAL_RUN)
    public Result<EvalRunEstimateResponse> estimate(@PathVariable String datasetId,
                                                    @Valid @RequestBody EvalRunSubmitRequest request) {
        kbResourceGuard.requireDatasetAccess(datasetId);
        AnswerEvaluationConfig answerConfig = request.answerEnabled()
                ? evalRunService.answerConfig(datasetId, request.answerAppVersionId()) : null;
        return Result.success(EvalRunEstimateResponse.from(evalRunService.estimate(
                datasetId, request.k(), request.toConfigs(), request.judgeEnabled(), answerConfig)));
    }

    /**
     * Lists the runs of a data set, newest first.
     *
     * @param datasetId data set business id
     * @param page      one based page number
     * @param size      page size
     * @return paged runs
     */
    @GetMapping("/api/v1/eval-datasets/{datasetId}/runs")
    @RequiresPermission(PermissionCodes.EVAL_READ)
    public Result<PageResponse<EvalRunResponse>> list(
            @PathVariable String datasetId,
            @RequestParam(name = "page", defaultValue = "" + DEFAULT_PAGE) long page,
            @RequestParam(name = "size", defaultValue = "" + DEFAULT_PAGE_SIZE) long size) {
        kbResourceGuard.requireDatasetAccess(datasetId);
        IPage<EvalRun> paged = evalRunService.listRuns(datasetId, normalizePage(page), normalizeSize(size));
        return Result.success(PageResponse.from(paged, EvalRunResponse::from));
    }

    /**
     * Loads a run's detail.
     *
     * @param runId run business id
     * @return run detail including its metrics
     */
    @GetMapping("/api/v1/eval-runs/{runId}")
    @RequiresPermission(PermissionCodes.EVAL_READ)
    public Result<EvalRunResponse> detail(@PathVariable String runId) {
        kbResourceGuard.requireRunAccess(runId);
        return Result.success(EvalRunResponse.from(evalRunService.requireRun(runId)));
    }

    /**
     * Pages a run's per case results, the report's drill down.
     *
     * @param runId run business id
     * @param hit   optional hit filter
     * @param page  one based page number
     * @param size  page size
     * @return paged results
     */
    @GetMapping("/api/v1/eval-runs/{runId}/results")
    @RequiresPermission(PermissionCodes.EVAL_READ)
    public Result<PageResponse<EvalResultResponse>> results(
            @PathVariable String runId,
            @RequestParam(required = false) Boolean hit,
            @RequestParam(name = "page", defaultValue = "" + DEFAULT_PAGE) long page,
            @RequestParam(name = "size", defaultValue = "" + DEFAULT_PAGE_SIZE) long size) {
        kbResourceGuard.requireRunAccess(runId);
        IPage<EvalResult> paged = evalRunService.results(runId, hit, normalizePage(page), normalizeSize(size));
        return Result.success(PageResponse.from(paged, EvalResultResponse::from));
    }

    /**
     * Compares the metrics of several runs.
     *
     * @param runIds comma separated run business ids
     * @return comparison outcome, {@code comparable=false} with a reason when the corpus moved
     */
    @GetMapping("/api/v1/eval-runs/compare")
    @RequiresPermission(PermissionCodes.EVAL_READ)
    public Result<EvalRunCompareResponse> compare(@RequestParam("run_ids") String runIds) {
        if (runIds == null || runIds.isBlank()) {
            throw BizException.invalidParam("run_ids must not be blank");
        }
        List<String> ids = Arrays.stream(runIds.split(",")).map(String::trim)
                .filter(id -> !id.isEmpty()).toList();
        ids.forEach(kbResourceGuard::requireRunAccess);
        return Result.success(EvalRunCompareResponse.from(evalRunService.compare(ids)));
    }

    private long normalizePage(long page) {
        return page < DEFAULT_PAGE ? DEFAULT_PAGE : page;
    }

    private long normalizeSize(long size) {
        if (size < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
