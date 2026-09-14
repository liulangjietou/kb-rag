package io.kbrag.api.controller;

import io.kbrag.api.annotation.RequiresPermission;
import io.kbrag.api.dto.EmployeeFeedbackResponse;
import io.kbrag.api.dto.PageResponse;
import io.kbrag.app.quality.EmployeeFeedbackService;
import io.kbrag.common.api.Result;
import io.kbrag.domain.constant.PermissionCodes;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

/** 员工负面反馈维护入口；应用管理读取权还由服务按 AND 关系检查。 */
@RestController
@RequestMapping("/api/v1/kb/{kbId}/employee-feedback")
@RequiresPermission(PermissionCodes.FEEDBACK_MANAGE)
@RequiredArgsConstructor
public class EmployeeFeedbackController {
    private static final int MAX_PAGE_SIZE = 50;
    private final EmployeeFeedbackService service;

    /** 反馈和资料权限实时变化，客户端不得缓存原回答。 */
    @ModelAttribute
    public void noStore(HttpServletResponse response) { response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store"); }

    /** 只列出当前范围中的完整负面回答，查询失败不能表示为没有反馈。 */
    @GetMapping
    public Result<PageResponse<EmployeeFeedbackResponse.Summary>> list(@PathVariable String kbId,
            @RequestParam(defaultValue = "1") long page, @RequestParam(defaultValue = "20") long size) {
        return Result.success(PageResponse.from(service.list(kbId, Math.max(1, page), Math.max(1, Math.min(MAX_PAGE_SIZE, size))),
                EmployeeFeedbackResponse.Summary::from));
    }

    /** 原回答逐次重验应用、知识库和全部引用权限。 */
    @GetMapping("/{runId}")
    public Result<EmployeeFeedbackResponse.Detail> detail(@PathVariable String kbId, @PathVariable String runId) {
        return Result.success(EmployeeFeedbackResponse.Detail.from(service.detail(kbId, runId)));
    }
}
