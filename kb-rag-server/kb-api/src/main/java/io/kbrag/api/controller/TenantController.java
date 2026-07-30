package io.kbrag.api.controller;

import io.kbrag.api.annotation.AuditedOperation;
import io.kbrag.api.annotation.RequiresPermission;
import io.kbrag.api.dto.SaveTenantRequest;
import io.kbrag.api.dto.TenantResponse;
import io.kbrag.api.dto.UpdateTenantStatusRequest;
import io.kbrag.app.auth.TenantService;
import io.kbrag.common.api.Result;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.constant.PermissionCodes;
import io.kbrag.domain.entity.Tenant;
import io.kbrag.domain.enums.TenantStatus;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Tenant administration endpoints, the only cross tenant view of the console.
 *
 * <p>Guarded as a whole by {@code tenant:manage}, which the seed grants to the super administrator
 * of the default tenant alone. There is deliberately no DELETE: a tenant owns indexes, files and
 * audit rows, so retirement means disable first and clean up by hand.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/tenants")
@RequiredArgsConstructor
@RequiresPermission(PermissionCodes.TENANT_MANAGE)
public class TenantController {

    private final TenantService tenantService;

    /**
     * Lists every tenant, the built in one first.
     *
     * @return tenants
     */
    @GetMapping
    public Result<List<TenantResponse>> list() {
        return Result.success(tenantService.list().stream()
                .map(TenantResponse::from)
                .toList());
    }

    /**
     * Loads one tenant.
     *
     * @param tenantId tenant business id
     * @return tenant view
     */
    @GetMapping("/{tenantId}")
    public Result<TenantResponse> get(@PathVariable String tenantId) {
        return Result.success(TenantResponse.from(tenantService.get(tenantId)));
    }

    /**
     * Creates a tenant seeded with copies of the five built in roles.
     *
     * @param request tenant definition
     * @return created tenant
     */
    @PostMapping
    @AuditedOperation(module = "TENANT", action = "CREATE", targetType = "TENANT",
            targetId = "#result.data.tenantId")
    public Result<TenantResponse> create(@Valid @RequestBody SaveTenantRequest request) {
        Tenant tenant = tenantService.create(request.code(), request.name());
        return Result.success(TenantResponse.from(tenant));
    }

    /**
     * Renames a tenant; the code is immutable.
     *
     * @param tenantId tenant business id
     * @param request  new display name, its code is ignored
     * @return updated tenant
     */
    @PutMapping("/{tenantId}")
    @AuditedOperation(module = "TENANT", action = "RENAME", targetType = "TENANT", targetId = "#tenantId")
    public Result<TenantResponse> rename(@PathVariable String tenantId,
                                         @Valid @RequestBody SaveTenantRequest request) {
        tenantService.rename(tenantId, request.name());
        return Result.success(TenantResponse.from(tenantService.get(tenantId)));
    }

    /**
     * Enables or disables a tenant; the default tenant cannot be disabled.
     *
     * @param tenantId tenant business id
     * @param request  new lifecycle state
     * @return empty success envelope
     */
    @PutMapping("/{tenantId}/status")
    @AuditedOperation(module = "TENANT", action = "UPDATE_STATUS", targetType = "TENANT", targetId = "#tenantId")
    public Result<Void> updateStatus(@PathVariable String tenantId,
                                     @Valid @RequestBody UpdateTenantStatusRequest request) {
        TenantStatus status = parseStatus(request.status());
        tenantService.updateStatus(tenantId, status);
        return Result.success(null);
    }

    private TenantStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            throw BizException.invalidParam("status 仅支持 ENABLED 或 DISABLED");
        }
        try {
            return TenantStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw BizException.invalidParam("status 仅支持 ENABLED 或 DISABLED");
        }
    }
}
