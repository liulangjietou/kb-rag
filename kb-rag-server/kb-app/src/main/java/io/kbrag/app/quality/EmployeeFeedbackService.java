package io.kbrag.app.quality;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.kbrag.app.auth.AccessGuard;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.constant.PermissionCodes;
import io.kbrag.domain.entity.EmployeeConversationRun;
import io.kbrag.domain.mapper.EmployeeFeedbackMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;

/** 负面评价入口复用运行账本；人工纠正和回归仍由知识质量问题服务编排。 */
@Service
@RequiredArgsConstructor
public class EmployeeFeedbackService {
    private final EmployeeFeedbackMapper feedback;
    private final QualityIssueAccess quality;
    private final EmployeeFeedbackAccess access;

    /** 资料撤权的行保留不透明标识；分页计数始终只覆盖当前租户与应用范围。 */
    public IPage<View> list(String kbId, long page, long size) {
        AccessGuard.requirePermission(PermissionCodes.FEEDBACK_MANAGE);
        AccessGuard.requirePermission(PermissionCodes.APP_READ);
        quality.requireKb(kbId);
        var principal = AccessGuard.currentUser();
        return feedback.pageBad(new Page<>(page, size), principal.tenantId(), kbId,
                principal.appScopeAll() ? null : new ArrayList<>(principal.appIds()))
                .convert(run -> view(kbId, run));
    }

    /** 只返回这一轮主动反馈的内容，不返回员工身份或整个会话。 */
    public EmployeeFeedbackAccess.Source detail(String kbId, String runId) {
        quality.requireKb(kbId);
        return access.read(kbId, runId);
    }

    private View view(String kbId, EmployeeConversationRun row) {
        try {
            // 逐行读取最新评价及权限；分页查询后刚撤回的 BAD 也不能继续展示旧正文。
            var source = access.read(kbId, row.getRunId());
            if (source.run().getFeedbackVerdict() != io.kbrag.domain.enums.FeedbackVerdict.BAD) return new View(row.getRunId(), null);
            return new View(row.getRunId(), source.run());
        } catch (BizException failure) {
            if (failure.getErrorCode() == ErrorCode.FORBIDDEN || failure.getErrorCode() == ErrorCode.NOT_FOUND) {
                return new View(row.getRunId(), null);
            }
            throw failure;
        }
    }

    public record View(String runId, EmployeeConversationRun readable) { }
}
