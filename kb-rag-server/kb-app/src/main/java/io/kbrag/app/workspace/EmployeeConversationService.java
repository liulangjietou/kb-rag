package io.kbrag.app.workspace;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.kbrag.domain.entity.EmployeeConversation;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.enums.FeedbackVerdict;
import io.kbrag.domain.model.EmployeeConversationScope;
import io.kbrag.domain.model.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** 员工会话入口：当前授权、短事务账本、后台执行与安全历史投影的编排。 */
@Service
@RequiredArgsConstructor
public class EmployeeConversationService {
    private final EmployeeWorkspaceAccess access;
    private final EmployeeConversationLedger ledger;
    private final EmployeeConversationHistory history;
    private final EmployeeRunCoordinator coordinator;

    /** 列出当前员工在该应用中的私有会话。 */
    public IPage<EmployeeConversation> list(String appId, String keyword, long page, long size) {
        UserPrincipal principal = access.current();
        return ledger.list(access.scope(principal, appId), keyword, page, size);
    }

    /** 创建只包含标题的会话，直到用户发送问题前不会产生模型调用。 */
    public EmployeeConversation create(String appId, String title) {
        UserPrincipal principal = access.current();
        return ledger.create(access.scope(principal, appId), title);
    }

    /** 读取会话摘要，支持没有历史消息的空会话。 */
    public EmployeeConversation get(String appId, String conversationId) {
        UserPrincipal principal = access.current();
        return ledger.conversation(access.scope(principal, appId), conversationId);
    }

    /** 更新标题，保存不会影响正在运行的问题。 */
    public EmployeeConversation rename(String appId, String conversationId, String title) {
        UserPrincipal principal = access.current();
        return ledger.rename(access.scope(principal, appId), conversationId, title);
    }

    /** 删除自己的会话并停止该会话仍在执行的上游。 */
    public void delete(String appId, String conversationId) {
        UserPrincipal principal = access.current();
        String runId = ledger.delete(access.scope(principal, appId), conversationId);
        if (runId != null) coordinator.cancelCommittedRun(runId);
    }

    /** 首次接受才调度，重放请求只返回原运行的当前持久视图。 */
    public EmployeeConversationHistory.RunView submit(String appId, String conversationId, String requestId, String question) {
        UserPrincipal principal = access.current();
        EmployeeConversationScope scope = access.scope(principal, appId);
        var accepted = ledger.acceptCurrent(scope, conversationId, requestId, question,
                () -> access.releasedTarget(principal, appId));
        coordinator.submit(scope, principal, accepted);
        return history.present(principal, ledger.get(scope, conversationId, accepted.run().getRunId()));
    }

    /** 只读取运行，刷新或重新订阅不能触发调度。 */
    public EmployeeConversationHistory.RunView run(String appId, String conversationId, String runId) {
        return runFor(access.current(), appId, conversationId, runId);
    }

    /** 订阅线程没有 HTTP ThreadLocal，使用订阅时的稳定身份重新解析授权后读取。 */
    public EmployeeConversationHistory.RunView subscriptionRun(UserPrincipal subscribedBy, String appId,
                                                                String conversationId, String runId) {
        return runFor(access.refresh(subscribedBy), appId, conversationId, runId);
    }

    /** 当前仍有应用使用权的用户可以停止自己的运行，即使引用内容已经撤权。 */
    public EmployeeConversationHistory.RunView stop(String appId, String conversationId, String runId) {
        UserPrincipal principal = access.current();
        EmployeeConversationScope scope = access.scope(principal, appId);
        coordinator.stop(scope, conversationId, runId);
        return history.present(principal, ledger.get(scope, conversationId, runId));
    }

    /** 当前身份只能评价自己的可读回答；评价不进入模型上下文或重新触发生成。 */
    public EmployeeConversationHistory.RunView feedback(String appId, String conversationId, String runId,
                                                        FeedbackVerdict verdict, String note, int expectedRevision) {
        UserPrincipal principal = access.current();
        EmployeeConversationScope scope = access.scope(principal, appId);
        if (history.present(principal, ledger.get(scope, conversationId, runId)).restricted()) {
            throw BizException.forbidden("资料权限或状态已变化，请重新读取回答后再评价");
        }
        return history.present(principal, ledger.feedback(scope, conversationId, runId, verdict, note, expectedRevision));
    }

    /** 按轮次分页返回经过当前证据权限过滤的历史。 */
    public List<EmployeeConversationHistory.RunView> history(String appId, String conversationId, int beforeTurn, int limit) {
        UserPrincipal principal = access.current();
        EmployeeConversationScope scope = access.scope(principal, appId);
        return ledger.history(scope, conversationId, beforeTurn, limit).stream()
                .map(run -> history.present(principal, run)).toList();
    }

    private EmployeeConversationHistory.RunView runFor(UserPrincipal principal, String appId, String conversationId, String runId) {
        return history.present(principal, ledger.get(access.scope(principal, appId), conversationId, runId));
    }
}
