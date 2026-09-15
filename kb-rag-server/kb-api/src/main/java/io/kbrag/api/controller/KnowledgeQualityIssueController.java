package io.kbrag.api.controller;

import io.kbrag.api.annotation.RequiresPermission;
import io.kbrag.api.dto.PageResponse;
import io.kbrag.api.dto.QualityIssueRecordResponse;
import io.kbrag.api.dto.QualityIssueRequests;
import io.kbrag.api.dto.QualityIssueResponse;
import io.kbrag.api.dto.QualityRegressionResponse;
import io.kbrag.app.quality.KnowledgeQualityIssueService;
import io.kbrag.common.api.Result;
import io.kbrag.domain.constant.PermissionCodes;
import io.kbrag.domain.enums.QualityIssueStatus;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

/** 知识库质量问题入口，处理权限与评测写入权限分别校验。 */
@RestController
@RequestMapping("/api/v1/kb/{kbId}/quality-issues")
@RequiresPermission(PermissionCodes.FEEDBACK_MANAGE)
@RequiredArgsConstructor
public class KnowledgeQualityIssueController {
    private static final int MAX_PAGE_SIZE = 50;
    private final KnowledgeQualityIssueService service;

    /** 状态与证据权限随时可能变化，浏览器不得复用旧内容缓存。 */
    @ModelAttribute
    public void noStore(HttpServletResponse response) { response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store"); }

    /** 从已有真实信号创建问题，重复来源复用已有记录。 */
    @PostMapping
    public Result<QualityIssueResponse> create(@PathVariable String kbId, @Valid @RequestBody QualityIssueRequests.Create request) {
        return Result.success(QualityIssueResponse.from(service.create(kbId, request.sourceType(), request.sourceId()), true, false));
    }

    /** 按阶段或本人负责筛选，受限资料只返回处理状态。 */
    @GetMapping
    public Result<PageResponse<QualityIssueResponse>> list(@PathVariable String kbId,
            @RequestParam(required = false) QualityIssueStatus status,
            @RequestParam(defaultValue = "false") boolean mine,
            @RequestParam(defaultValue = "1") long page, @RequestParam(defaultValue = "20") long size) {
        return Result.success(PageResponse.from(service.list(kbId, status, mine, Math.max(1, page),
                Math.max(1, Math.min(MAX_PAGE_SIZE, size))), view -> QualityIssueResponse.from(view.issue(), view.readable(), false)));
    }

    /** 返回当前状态和人工确认的纠正用例。 */
    @GetMapping("/{issueId}")
    public Result<QualityIssueResponse> detail(@PathVariable String kbId, @PathVariable String issueId) {
        var detail = service.detail(kbId, issueId);
        return Result.success(QualityIssueResponse.from(detail.issue(), true, true, detail.currentCase()));
    }

    /** 处理记录独立分页。 */
    @GetMapping("/{issueId}/records")
    public Result<PageResponse<QualityIssueRecordResponse>> records(@PathVariable String kbId, @PathVariable String issueId,
            @RequestParam(defaultValue = "1") long page) {
        return Result.success(PageResponse.from(service.history(kbId, issueId, Math.max(1, page)), QualityIssueRecordResponse::from));
    }

    /** 领取时使用界面读取的修订号。 */
    @PostMapping("/{issueId}/claim")
    public Result<QualityIssueResponse> claim(@PathVariable String kbId, @PathVariable String issueId,
            @Valid @RequestBody QualityIssueRequests.Revision request) {
        return Result.success(QualityIssueResponse.from(service.claim(kbId, issueId, request.revision()), true, false));
    }

    /** 当前负责人释放。 */
    @PostMapping("/{issueId}/release")
    public Result<QualityIssueResponse> release(@PathVariable String kbId, @PathVariable String issueId,
            @Valid @RequestBody QualityIssueRequests.Revision request) {
        return Result.success(QualityIssueResponse.from(service.release(kbId, issueId, request.revision()), true, false));
    }

    /** 追加处理说明。 */
    @PostMapping("/{issueId}/notes")
    public Result<QualityIssueResponse> note(@PathVariable String kbId, @PathVariable String issueId,
            @Valid @RequestBody QualityIssueRequests.Note request) {
        return Result.success(QualityIssueResponse.from(service.addNote(kbId, issueId, request.revision(), request.note().trim()), true, false));
    }

    /** 纠正操作额外要求评测读写和应用读取权限，由服务按 AND 关系检查。 */
    @PostMapping("/{issueId}/correction")
    public Result<QualityIssueResponse> correct(@PathVariable String kbId, @PathVariable String issueId,
            @Valid @RequestBody QualityIssueRequests.Correction request) {
        return Result.success(QualityIssueResponse.from(service.correct(kbId, issueId, request.toCommand()), true, true));
    }

    /** 核验真实回归并记录人工确认。 */
    @PostMapping("/{issueId}/resolve")
    public Result<QualityIssueResponse> resolve(@PathVariable String kbId, @PathVariable String issueId,
            @Valid @RequestBody QualityIssueRequests.Resolve request) {
        return Result.success(QualityIssueResponse.from(service.resolve(kbId, issueId, request.revision(), request.runId(), request.note().trim()), true, false));
    }

    /** 负责人在解决前可核对已通过边界检查的具体答案与引用评分。 */
    @GetMapping("/{issueId}/regressions/{runId}")
    public Result<QualityRegressionResponse> regression(@PathVariable String kbId, @PathVariable String issueId,
            @PathVariable String runId) {
        return Result.success(QualityRegressionResponse.from(service.previewRegression(kbId, issueId, runId)));
    }

    /** 再次出现时说明原因并重新打开。 */
    @PostMapping("/{issueId}/reopen")
    public Result<QualityIssueResponse> reopen(@PathVariable String kbId, @PathVariable String issueId,
            @Valid @RequestBody QualityIssueRequests.Note request) {
        return Result.success(QualityIssueResponse.from(service.reopen(kbId, issueId, request.revision(), request.note().trim()), true, false));
    }
}
