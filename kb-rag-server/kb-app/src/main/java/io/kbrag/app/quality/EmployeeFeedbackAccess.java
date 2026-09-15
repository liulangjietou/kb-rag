package io.kbrag.app.quality;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import io.kbrag.app.auth.AccessGuard;
import io.kbrag.app.workspace.EmployeeEvidenceService;
import io.kbrag.app.workspace.EmployeeWorkspaceAccess;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.constant.PermissionCodes;
import io.kbrag.domain.entity.EmployeeConversation;
import io.kbrag.domain.entity.EmployeeConversationRun;
import io.kbrag.domain.entity.KnowledgeQualityIssue;
import io.kbrag.domain.enums.ConversationRunStatus;
import io.kbrag.domain.enums.FeedbackVerdict;
import io.kbrag.domain.enums.QualityIssueSource;
import io.kbrag.domain.mapper.EmployeeConversationMapper;
import io.kbrag.domain.mapper.EmployeeConversationRunMapper;
import io.kbrag.domain.mapper.KnowledgeQualityIssueMapper;
import io.kbrag.domain.model.AppConfigSnapshot;
import io.kbrag.domain.model.EmployeeCitation;
import io.kbrag.domain.model.EmployeeRunTarget;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/** 员工主动反馈的整轮内容授权；处理人必须能读取应用及全部当前和继承证据。 */
@Component
@RequiredArgsConstructor
public class EmployeeFeedbackAccess {
    private final EmployeeConversationRunMapper runs;
    private final EmployeeConversationMapper conversations;
    private final EmployeeWorkspaceAccess workspace;
    private final EmployeeEvidenceService evidence;
    private final KnowledgeQualityIssueMapper issues;

    /** 读取来源的最新评价；评价改为 GOOD 后仍可核对已有问题，不再接受创建新问题。 */
    public Source read(String kbId, String runId) { return require(kbId, runId, false); }

    /** 创建事务与员工反馈遵守相同的会话、运行取锁顺序，避免采用已撤回的 BAD。 */
    public Source lockBad(String kbId, String runId) {
        Source source = require(kbId, runId, true);
        if (source.run().getFeedbackVerdict() != FeedbackVerdict.BAD) {
            throw BizException.invalidParam("员工已修改评价，请刷新后再处理");
        }
        return source;
    }

    private Source require(String kbId, String runId, boolean lock) {
        AccessGuard.requirePermission(PermissionCodes.FEEDBACK_MANAGE);
        AccessGuard.requirePermission(PermissionCodes.APP_READ);
        var principal = AccessGuard.currentUser();
        var runQuery = new LambdaQueryWrapper<EmployeeConversationRun>()
                .eq(EmployeeConversationRun::getTenantId, principal.tenantId()).eq(EmployeeConversationRun::getRunId, runId);
        EmployeeConversationRun run = runs.selectOne(runQuery);
        if (run == null) throw BizException.notFound("employee feedback not found");
        var conversationQuery = new LambdaQueryWrapper<EmployeeConversation>()
                .eq(EmployeeConversation::getTenantId, principal.tenantId())
                .eq(EmployeeConversation::getConversationId, run.getConversationId())
                .eq(EmployeeConversation::getAppId, run.getAppId()).eq(EmployeeConversation::getUserId, run.getUserId());
        if (lock) conversationQuery.last("FOR UPDATE");
        if (conversations.selectOne(conversationQuery) == null) throw BizException.notFound("employee feedback not found");
        if (lock) run = runs.selectOne(runQuery.last("FOR UPDATE"));
        if (run == null || run.getStatus() != ConversationRunStatus.SUCCEEDED || run.getFeedbackVerdict() == null) {
            throw BizException.notFound("employee feedback not found");
        }
        EmployeeRunTarget target = JsonUtil.parse(run.getTargetJson(), EmployeeRunTarget.class);
        if (!Objects.equals(target.appId(), run.getAppId())
                || !JsonUtil.parse(target.config(), AppConfigSnapshot.class).kbIds().contains(kbId)) {
            throw BizException.notFound("employee feedback not found");
        }
        workspace.requireTarget(principal, target);
        // 问题表以知识库为租户根；先重验快照中的知识库根，再查看是否已有同源问题。
        if (run.getFeedbackVerdict() != FeedbackVerdict.BAD && issues.selectCount(new LambdaQueryWrapper<KnowledgeQualityIssue>()
                .eq(KnowledgeQualityIssue::getKbId, kbId).eq(KnowledgeQualityIssue::getSourceType, QualityIssueSource.EMPLOYEE_ANSWER)
                .eq(KnowledgeQualityIssue::getSourceId, runId)) == 0) {
            throw BizException.notFound("employee negative feedback not found");
        }
        List<EmployeeCitation> citations = JsonUtil.parse(run.getReferencesJson(), new TypeReference<List<EmployeeCitation>>() { });
        if (!evidence.canReadAll(principal, citations)) throw BizException.forbidden("回答依赖的资料已不可访问");
        return new Source(run, target.appVersion(), List.copyOf(citations), target.appVersionId());
    }

    /** 内部已授权内容，不直接序列化实体和配置快照。 */
    public record Source(EmployeeConversationRun run, String appVersion, List<EmployeeCitation> citations, String appVersionId) { }
}
