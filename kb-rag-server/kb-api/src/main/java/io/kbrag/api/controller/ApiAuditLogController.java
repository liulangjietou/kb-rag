package io.kbrag.api.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.kbrag.api.dto.ApiAuditLogResponse;
import io.kbrag.api.dto.ApiAuditStatsResponse;
import io.kbrag.api.dto.PageResponse;
import io.kbrag.app.openapi.ApiAuditService;
import io.kbrag.common.api.Result;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.entity.ApiAuditLog;
import io.kbrag.domain.enums.TargetStage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

/**
 * Audit query and call volume statistics of the console, requirement section 4.8.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/api-audit-logs")
@RequiredArgsConstructor
public class ApiAuditLogController {

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;

    private final ApiAuditService apiAuditService;

    /**
     * Pages the audit trail, newest first.
     *
     * @param keyId       optional API key filter
     * @param targetStage optional {@code release} or {@code beta} filter
     * @param from        optional ISO lower bound of the call time
     * @param to          optional ISO upper bound of the call time
     * @param page        one based page number
     * @param size        page size
     * @return paged rows
     */
    @GetMapping
    public Result<PageResponse<ApiAuditLogResponse>> list(
            @RequestParam(name = "key_id", required = false) String keyId,
            @RequestParam(name = "target_stage", required = false) String targetStage,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(name = "page", defaultValue = "" + DEFAULT_PAGE) long page,
            @RequestParam(name = "size", defaultValue = "" + DEFAULT_PAGE_SIZE) long size) {
        IPage<ApiAuditLog> paged = apiAuditService.query(keyId, parseStage(targetStage),
                parseTime(from, "from"), parseTime(to, "to"), normalizePage(page), normalizeSize(size));
        return Result.success(PageResponse.from(paged, ApiAuditLogResponse::from));
    }

    /**
     * Aggregates the call volume of the same filter the listing accepts.
     *
     * @param keyId       optional API key filter
     * @param targetStage optional {@code release} or {@code beta} filter
     * @param from        optional ISO lower bound of the call time
     * @param to          optional ISO upper bound of the call time
     * @return totals of the filtered rows
     */
    @GetMapping("/stats")
    public Result<ApiAuditStatsResponse> stats(
            @RequestParam(name = "key_id", required = false) String keyId,
            @RequestParam(name = "target_stage", required = false) String targetStage,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return Result.success(ApiAuditStatsResponse.from(apiAuditService.stats(keyId, parseStage(targetStage),
                parseTime(from, "from"), parseTime(to, "to"))));
    }

    /**
     * Parses a stage filter, accepting both the API literal and the enum name.
     *
     * @param value request literal
     * @return stage, {@code null} when unfiltered
     */
    private TargetStage parseStage(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        TargetStage stage = TargetStage.from(value);
        if (stage == null) {
            throw BizException.invalidParam("target_stage 仅支持 release 或 beta");
        }
        return stage;
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
