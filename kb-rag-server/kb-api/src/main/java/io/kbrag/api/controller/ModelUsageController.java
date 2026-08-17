package io.kbrag.api.controller;

import io.kbrag.api.annotation.AuditedOperation;
import io.kbrag.api.annotation.RequiresPermission;
import io.kbrag.api.dto.ModelPriceRequest;
import io.kbrag.api.dto.ModelPriceResponse;
import io.kbrag.api.dto.ModelUsageRecordResponse;
import io.kbrag.api.dto.ModelUsageSummaryResponse;
import io.kbrag.api.dto.PageResponse;
import io.kbrag.app.modelusage.ModelUsageService;
import io.kbrag.common.api.Result;
import io.kbrag.domain.constant.PermissionCodes;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Platform operator view of tenant model quotas, usage ledger and price configuration.
 *
 * <p>The whole surface is platform-only because it crosses tenant boundaries and changes global
 * prices. Tenant accounts cannot acquire {@code tenant:manage}, enforced at the role grant boundary.
 *
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/model-usage")
@RequiresPermission(PermissionCodes.TENANT_MANAGE)
@RequiredArgsConstructor
public class ModelUsageController {

    private static final long MAX_PAGE_SIZE = 200L;

    private final ModelUsageService modelUsageService;

    /** Returns one tenant-month summary; blank month means the current Asia/Shanghai month. */
    @GetMapping("/summary")
    public Result<ModelUsageSummaryResponse> summary(@RequestParam("tenant_id") String tenantId,
                                                     @RequestParam(required = false) String month) {
        return Result.success(ModelUsageSummaryResponse.from(modelUsageService.summary(tenantId, month)));
    }

    /** Pages the safe usage ledger dimensions of one tenant-month. */
    @GetMapping("/records")
    public Result<PageResponse<ModelUsageRecordResponse>> records(
            @RequestParam("tenant_id") String tenantId,
            @RequestParam(required = false) String month,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        long safePage = Math.max(1L, page);
        long safeSize = Math.max(1L, Math.min(size, MAX_PAGE_SIZE));
        return Result.success(PageResponse.from(
                modelUsageService.records(tenantId, month, safePage, safeSize), ModelUsageRecordResponse::from));
    }

    /** Lists every price configuration. */
    @GetMapping("/prices")
    public Result<List<ModelPriceResponse>> listPrices() {
        return Result.success(modelUsageService.listPrices().stream().map(ModelPriceResponse::from).toList());
    }

    /** Upserts one price tuple; prior ledger rows keep their snapshots. */
    @PutMapping("/prices")
    @AuditedOperation(module = "MODEL_USAGE", action = "SAVE_PRICE",
            targetType = "MODEL_PRICE", targetId = "#request.provider + ':' + #request.capability + ':' + #request.model")
    public Result<ModelPriceResponse> savePrice(@Valid @RequestBody ModelPriceRequest request) {
        return Result.success(ModelPriceResponse.from(modelUsageService.savePrice(
                request.provider(), request.capability(), request.model(), request.currency(),
                request.inputPriceMicros(), request.outputPriceMicros(), request.enabled())));
    }
}
