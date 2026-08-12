package io.kbrag.api.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.kbrag.api.annotation.RequiresPermission;
import io.kbrag.api.dto.PageResponse;
import io.kbrag.api.dto.SearchInsightResponse;
import io.kbrag.api.dto.SearchInsightStatsResponse;
import io.kbrag.app.auth.KbResourceGuard;
import io.kbrag.app.insight.SearchInsightService;
import io.kbrag.common.api.Result;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.constant.PermissionCodes;
import io.kbrag.domain.entity.SearchInsight;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

/**
 * Search insight listing and content gap report of the console, the M10 contract section 2.2.
 *
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/kb/{kbId}/search-insights")
@RequiredArgsConstructor
@RequiresPermission({PermissionCodes.FEEDBACK_MANAGE, PermissionCodes.AUDIT_READ})
public class SearchInsightController {

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;

    private final SearchInsightService searchInsightService;
    private final KbResourceGuard kbResourceGuard;

    /**
     * Pages the insight rows of one knowledge base, newest first.
     *
     * @param kbId    knowledge base business id
     * @param zeroHit optional zero hit filter
     * @param from    optional ISO lower bound of the call time
     * @param to      optional ISO upper bound of the call time
     * @param page    one based page number
     * @param size    page size
     * @return paged rows
     */
    @GetMapping
    public Result<PageResponse<SearchInsightResponse>> list(
            @PathVariable String kbId,
            @RequestParam(name = "zero_hit", required = false) Boolean zeroHit,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(name = "page", defaultValue = "" + DEFAULT_PAGE) long page,
            @RequestParam(name = "size", defaultValue = "" + DEFAULT_PAGE_SIZE) long size) {
        kbResourceGuard.requireKb(kbId);
        IPage<SearchInsight> paged = searchInsightService.list(kbId, zeroHit,
                parseTime(from, "from"), parseTime(to, "to"), normalizePage(page), normalizeSize(size));
        return Result.success(PageResponse.from(paged, SearchInsightResponse::from));
    }

    /**
     * Aggregates the content gap report of one knowledge base.
     *
     * @param kbId knowledge base business id
     * @param from optional ISO lower bound, defaults to seven days back
     * @param to   optional ISO upper bound
     * @return totals plus the top zero hit query groups
     */
    @GetMapping("/stats")
    public Result<SearchInsightStatsResponse> stats(
            @PathVariable String kbId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        kbResourceGuard.requireKb(kbId);
        return Result.success(SearchInsightStatsResponse.from(
                searchInsightService.stats(kbId, parseTime(from, "from"), parseTime(to, "to"))));
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
