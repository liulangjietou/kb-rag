package io.kbrag.api.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.api.annotation.RequiresPermission;
import io.kbrag.api.dto.EmployeeConversationResponse;
import io.kbrag.api.dto.EmployeeRunResponse;
import io.kbrag.api.dto.PageResponse;
import io.kbrag.api.sse.EmployeeRunSubscriptions;
import io.kbrag.app.workspace.EmployeeConversationService;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.api.Result;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.constant.PermissionCodes;
import io.kbrag.domain.enums.FeedbackVerdict;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import org.apache.commons.lang3.StringUtils;

/** 员工正式问答：提交命令与订阅读取分离，刷新连接永不产生新的模型调用。 */
@RestController
@RequestMapping("/api/v1/workspace/apps/{appId}/conversations")
@RequiresPermission(PermissionCodes.APP_USE)
@RequiredArgsConstructor
public class EmployeeConversationController {
    private static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_SEARCH_LENGTH = 120;
    private static final int MAX_PAGE_NUMBER = 100_000;
    private final EmployeeConversationService conversations;
    private final EmployeeRunSubscriptions subscriptions;

    /** 当前授权投影不能被 HTTP 缓存作为旧权限下的内容复用。 */
    @ModelAttribute
    public void preventContentCaching(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    /** 搜索标题和自己提交过的问题，分页不会读取受限的答案正文。 */
    @GetMapping
    public Result<PageResponse<EmployeeConversationResponse>> list(@PathVariable String appId,
            @RequestParam(required = false) String keyword, @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") int size) {
        requirePage(page, size);
        if (keyword != null && keyword.length() > MAX_SEARCH_LENGTH) throw invalid("搜索内容最多 120 个字符");
        return Result.success(PageResponse.from(conversations.list(appId, keyword, page, size), EmployeeConversationResponse::from));
    }

    /** 创建空会话不执行模型。 */
    @PostMapping
    public Result<EmployeeConversationResponse> create(@PathVariable String appId, @Valid @RequestBody TitleRequest request) {
        return Result.success(EmployeeConversationResponse.from(conversations.create(appId, request.title().strip())));
    }

    /** 返回当前会话摘要，包括另一个标签页启动的活动运行。 */
    @GetMapping("/{conversationId}")
    public Result<EmployeeConversationResponse> get(@PathVariable String appId, @PathVariable String conversationId) {
        return Result.success(EmployeeConversationResponse.from(conversations.get(appId, conversationId)));
    }

    /** 修改标题不会中断回答，也不会用旧摘要覆盖活动状态。 */
    @PatchMapping("/{conversationId}")
    public Result<EmployeeConversationResponse> rename(@PathVariable String appId, @PathVariable String conversationId,
                                                        @Valid @RequestBody TitleRequest request) {
        return Result.success(EmployeeConversationResponse.from(conversations.rename(appId, conversationId, request.title().strip())));
    }

    /** 删除自己的会话，同时持久化取消活动运行。 */
    @DeleteMapping("/{conversationId}")
    public Result<Void> delete(@PathVariable String appId, @PathVariable String conversationId) {
        conversations.delete(appId, conversationId);
        return Result.success(null);
    }

    /** 倒序历史使用轮次游标，新增运行不会让向前翻页漏掉历史。 */
    @GetMapping("/{conversationId}/runs")
    public Result<List<EmployeeRunResponse>> history(@PathVariable String appId, @PathVariable String conversationId,
            @RequestParam(name = "before_turn", defaultValue = "2147483647") int beforeTurn,
            @RequestParam(defaultValue = "20") int limit) {
        requirePage(1, limit);
        if (beforeTurn < 1) throw invalid("历史轮次必须大于零");
        return Result.success(conversations.history(appId, conversationId, beforeTurn, limit).stream()
                .map(EmployeeRunResponse::from).toList());
    }

    /** 请求标识在网络重试中保持不变；用户主动重试必须提供新的标识。 */
    @PostMapping("/{conversationId}/runs")
    public Result<EmployeeRunResponse> submit(@PathVariable String appId, @PathVariable String conversationId,
                                                @Valid @RequestBody RunRequest request) {
        return Result.success(EmployeeRunResponse.from(conversations.submit(appId, conversationId, request.requestId(), request.query())));
    }

    /** GET 只读取当前持久状态，包括失败、中断和停止后保留的回答。 */
    @GetMapping("/{conversationId}/runs/{runId}")
    public Result<EmployeeRunResponse> run(@PathVariable String appId, @PathVariable String conversationId, @PathVariable String runId) {
        return Result.success(EmployeeRunResponse.from(conversations.run(appId, conversationId, runId)));
    }

    /** 明确停止会先提交终态，再取消上游；断开订阅不会调用此操作。 */
    @PostMapping("/{conversationId}/runs/{runId}/stop")
    public Result<EmployeeRunResponse> stop(@PathVariable String appId, @PathVariable String conversationId, @PathVariable String runId) {
        return Result.success(EmployeeRunResponse.from(conversations.stop(appId, conversationId, runId)));
    }

    /** 保存最新评价；客户端必须携带所见修订号，避免不同页面静默覆盖。 */
    @PutMapping("/{conversationId}/runs/{runId}/feedback")
    public Result<EmployeeRunResponse> feedback(@PathVariable String appId, @PathVariable String conversationId,
            @PathVariable String runId, @Valid @RequestBody FeedbackRequest request) {
        return Result.success(EmployeeRunResponse.from(conversations.feedback(appId, conversationId, runId,
                request.verdict(), StringUtils.stripToNull(request.note()), request.expectedRevision())));
    }

    /** 连接只发送已提交快照，重连从相同运行恢复；终态提交后才会发送 done。 */
    @GetMapping(value = "/{conversationId}/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String appId, @PathVariable String conversationId, @PathVariable String runId,
                              HttpServletResponse response) {
        response.setHeader("X-Accel-Buffering", "no");
        return subscriptions.subscribe(appId, conversationId, runId);
    }

    /** 非数字的分页参数按输入错误返回，避免落入通用 500 处理。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> invalidQuery() {
        return ResponseEntity.badRequest().body(Result.failure(ErrorCode.INVALID_PARAM, "分页参数必须为有效整数"));
    }

    private static void requirePage(long page, int size) {
        if (page < 1 || page > MAX_PAGE_NUMBER || size < 1 || size > MAX_PAGE_SIZE) throw invalid("分页参数超出允许范围");
    }

    private static BizException invalid(String message) {
        return new BizException(ErrorCode.INVALID_PARAM, message);
    }

    /** 标题长度与持久字段一致。 */
    public record TitleRequest(@NotBlank @Size(max = 120) String title) { }

    /** 模型历史、正式版本、引用及内部配置只能由服务端生成。 */
    public record RunRequest(@JsonProperty("request_id") @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{8,128}") String requestId,
                             @NotBlank @Size(max = 8000) String query) { }

    /** 说明按用户主动提交记录，版本和运行归属由服务端验证。 */
    public record FeedbackRequest(@NotNull FeedbackVerdict verdict, @Size(max = 512) String note,
            @JsonProperty("expected_revision") @NotNull @Min(0) Integer expectedRevision) { }
}
