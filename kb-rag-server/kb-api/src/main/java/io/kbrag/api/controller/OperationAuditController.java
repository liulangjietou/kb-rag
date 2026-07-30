package io.kbrag.api.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.kbrag.api.annotation.RequiresPermission;
import io.kbrag.api.dto.OperationAuditResponse;
import io.kbrag.api.dto.PageResponse;
import io.kbrag.app.audit.OperationAuditService;
import io.kbrag.common.api.Result;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.constant.PermissionCodes;
import io.kbrag.domain.entity.OperationAudit;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

/**
 * Read side of the operation audit trail, the M16 contract section 3.7.
 *
 * <p>Read only by design: audit rows are written exclusively by the aspect behind the annotated
 * write endpoints, and an API that could edit its own trail would not be one. The class level
 * {@code audit:read} guard mirrors the API audit log screen this view sits next to.
 *
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/operation-audits")
@RequiredArgsConstructor
@RequiresPermission(PermissionCodes.AUDIT_READ)
public class OperationAuditController {

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;

    private final OperationAuditService operationAuditService;

    /**
     * Pages the trail of the caller's tenant, newest first.
     *
     * @param module   optional module filter
     * @param username optional operator login name filter
     * @param targetId optional target business id filter
     * @param from     optional ISO lower bound of the operation time
     * @param to       optional ISO upper bound of the operation time
     * @param page     one based page number
     * @param size     page size
     * @return paged rows
     */
    @GetMapping
    public Result<PageResponse<OperationAuditResponse>> list(
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String username,
            @RequestParam(name = "target_id", required = false) String targetId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(name = "page", defaultValue = "" + DEFAULT_PAGE) long page,
            @RequestParam(name = "size", defaultValue = "" + DEFAULT_PAGE_SIZE) long size) {
        IPage<OperationAudit> paged = operationAuditService.list(module, username, targetId,
                parseTime(from, "from"), parseTime(to, "to"), normalizePage(page), normalizeSize(size));
        return Result.success(PageResponse.from(paged, OperationAuditResponse::from));
    }

    /**
     * Reads one record of the caller's tenant.
     *
     * @param auditId audit business id
     * @return the record
     */
    @GetMapping("/{auditId}")
    public Result<OperationAuditResponse> detail(@PathVariable String auditId) {
        return Result.success(OperationAuditResponse.from(operationAuditService.require(auditId)));
    }

    /**
     * Parses an ISO local date time bound.
     *
     * @param value request literal
     * @param field field name, for the rejection message
     * @return timestamp, {@code null} when unfiltered
     */
    private LocalDateTime parseTime(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw BizException.invalidParam(field + " 需为 ISO 格式的日期时间，例如 2026-07-26T00:00:00");
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
