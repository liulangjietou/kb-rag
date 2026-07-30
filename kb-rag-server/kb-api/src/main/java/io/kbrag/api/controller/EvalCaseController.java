package io.kbrag.api.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.kbrag.api.annotation.AuditedOperation;
import io.kbrag.api.annotation.RequiresPermission;
import io.kbrag.api.dto.EvalCaseRequest;
import io.kbrag.api.dto.EvalCaseResponse;
import io.kbrag.api.dto.EvalRecheckRequest;
import io.kbrag.api.dto.FromRetrievalRequest;
import io.kbrag.api.dto.PageResponse;
import io.kbrag.api.dto.StaleCaseResponse;
import io.kbrag.app.auth.KbScopeGuard;
import io.kbrag.app.eval.EvalCaseStalenessService;
import io.kbrag.app.eval.EvalDatasetService;
import io.kbrag.common.api.Result;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.constant.PermissionCodes;
import io.kbrag.domain.entity.EvalCase;
import io.kbrag.domain.enums.CaseStatus;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/**
 * Evaluation case management and evidence review endpoints, requirement section 4.5.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class EvalCaseController {

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;

    private final EvalDatasetService evalDatasetService;
    private final EvalCaseStalenessService evalCaseStalenessService;
    private final KbScopeGuard kbScopeGuard;

    /**
     * Adds a case to a data set.
     *
     * @param datasetId data set business id
     * @param request   case payload
     * @return created case
     */
    @PostMapping("/api/v1/eval-datasets/{datasetId}/cases")
    @RequiresPermission(PermissionCodes.EVAL_WRITE)
    @AuditedOperation(module = "EVAL", action = "CREATE", targetType = "EVAL_CASE",
            targetId = "#result.data.caseId")
    public Result<EvalCaseResponse> create(@PathVariable String datasetId,
                                           @Valid @RequestBody EvalCaseRequest request) {
        kbScopeGuard.requireDatasetAccess(datasetId);
        return Result.success(EvalCaseResponse.from(
                evalDatasetService.createCase(datasetId, request.toCommand())));
    }

    /**
     * Pages the cases of a data set.
     *
     * @param datasetId data set business id
     * @param status    optional status filter
     * @param page      one based page number
     * @param size      page size
     * @return paged cases
     */
    @GetMapping("/api/v1/eval-datasets/{datasetId}/cases")
    @RequiresPermission(PermissionCodes.EVAL_READ)
    public Result<PageResponse<EvalCaseResponse>> list(
            @PathVariable String datasetId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "page", defaultValue = "" + DEFAULT_PAGE) long page,
            @RequestParam(name = "size", defaultValue = "" + DEFAULT_PAGE_SIZE) long size) {
        kbScopeGuard.requireDatasetAccess(datasetId);
        IPage<EvalCase> paged = evalDatasetService.listCases(datasetId, parseStatus(status),
                normalizePage(page), normalizeSize(size));
        return Result.success(PageResponse.from(paged, EvalCaseResponse::from));
    }

    /**
     * Collects a one click case from the retrieval debug page, requirement section 4.5.
     *
     * @param datasetId data set business id
     * @param request   query, history and the selected chunk ids
     * @return created case, {@code source=DEBUG_PAGE}
     */
    @PostMapping("/api/v1/eval-datasets/{datasetId}/cases/from-retrieval")
    @RequiresPermission(PermissionCodes.EVAL_WRITE)
    @AuditedOperation(module = "EVAL", action = "COLLECT", targetType = "EVAL_CASE",
            targetId = "#result.data.caseId")
    public Result<EvalCaseResponse> collectFromRetrieval(@PathVariable String datasetId,
                                                         @Valid @RequestBody FromRetrievalRequest request) {
        kbScopeGuard.requireDatasetAccess(datasetId);
        return Result.success(EvalCaseResponse.from(evalDatasetService.collectFromRetrieval(
                datasetId, request.query(), request.toMessages(), request.chunkIds(),
                request.parsedAnchorType())));
    }

    /**
     * Lists the stale cases of a data set together with their replacement candidates, the evidence
     * review workbench data.
     *
     * @param datasetId data set business id
     * @return one row per stale case
     */
    @GetMapping("/api/v1/eval-datasets/{datasetId}/stale-cases")
    @RequiresPermission(PermissionCodes.EVAL_READ)
    public Result<List<StaleCaseResponse>> staleCases(@PathVariable String datasetId) {
        kbScopeGuard.requireDatasetAccess(datasetId);
        evalDatasetService.require(datasetId);
        return Result.success(evalCaseStalenessService.staleCases(datasetId).stream()
                .map(StaleCaseResponse::from).toList());
    }

    /**
     * Replaces a case's payload.
     *
     * @param caseId  case business id
     * @param request new payload
     * @return updated case
     */
    @PutMapping("/api/v1/eval-cases/{caseId}")
    @RequiresPermission(PermissionCodes.EVAL_WRITE)
    @AuditedOperation(module = "EVAL", action = "UPDATE", targetType = "EVAL_CASE", targetId = "#caseId")
    public Result<EvalCaseResponse> update(@PathVariable String caseId,
                                           @Valid @RequestBody EvalCaseRequest request) {
        kbScopeGuard.requireCaseAccess(caseId);
        return Result.success(EvalCaseResponse.from(evalDatasetService.updateCase(caseId, request.toCommand())));
    }

    /**
     * Deletes a case.
     *
     * @param caseId case business id
     * @return empty payload
     */
    @DeleteMapping("/api/v1/eval-cases/{caseId}")
    @RequiresPermission(PermissionCodes.EVAL_WRITE)
    @AuditedOperation(module = "EVAL", action = "DELETE", targetType = "EVAL_CASE", targetId = "#caseId")
    public Result<Void> delete(@PathVariable String caseId) {
        kbScopeGuard.requireCaseAccess(caseId);
        evalDatasetService.deleteCase(caseId);
        return Result.success(null);
    }

    /**
     * Reviews a stale case: re-anchors it to a fresh excerpt or retires it.
     *
     * @param caseId  case business id
     * @param request reviewer decision
     * @return updated case
     */
    @PostMapping("/api/v1/eval-cases/{caseId}/recheck")
    @RequiresPermission(PermissionCodes.EVAL_WRITE)
    @AuditedOperation(module = "EVAL", action = "RECHECK", targetType = "EVAL_CASE", targetId = "#caseId")
    public Result<EvalCaseResponse> recheck(@PathVariable String caseId,
                                            @Valid @RequestBody EvalRecheckRequest request) {
        kbScopeGuard.requireCaseAccess(caseId);
        return Result.success(EvalCaseResponse.from(evalDatasetService.recheck(
                caseId, request.parsedAction(), request.toEvidences())));
    }

    private CaseStatus parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return CaseStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw BizException.invalidParam("status must be ACTIVE, EVIDENCE_STALE or DEPRECATED");
        }
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
