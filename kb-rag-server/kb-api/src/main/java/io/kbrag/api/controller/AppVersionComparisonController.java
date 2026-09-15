package io.kbrag.api.controller;

import io.kbrag.api.annotation.RequiresPermission;
import io.kbrag.api.dto.AppCorpusComparisonResponse;
import io.kbrag.app.appcenter.AppVersionComparisonService;
import io.kbrag.common.api.Result;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.constant.PermissionCodes;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 应用版本资料差异的独立只读入口，避免与发布写操作共享职责。 */
@RestController
@RequestMapping("/api/v1/app-versions")
@RequiredArgsConstructor
public class AppVersionComparisonController {
    private static final int MAX_PAGE_SIZE = 100;
    private final AppVersionComparisonService service;

    /** 对照同应用版本的资料集合；省略对照版本表示明确的空基线。 */
    @GetMapping("/{appVersionId}/corpus-diff")
    @RequiresPermission(PermissionCodes.APP_READ)
    public Result<AppCorpusComparisonResponse> corpusDiff(@PathVariable String appVersionId,
            @RequestParam(name = "baseline_id", required = false) String baselineId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
            HttpServletResponse response) {
        if (page < 1 || pageSize < 1 || pageSize > MAX_PAGE_SIZE
                || (baselineId != null && StringUtils.isBlank(baselineId))) {
            throw BizException.invalidParam("invalid comparison baseline or page");
        }
        response.setHeader("Cache-Control", "no-store");
        return Result.success(AppCorpusComparisonResponse.from(service.compareCorpus(appVersionId, baselineId, page, pageSize)));
    }
}
