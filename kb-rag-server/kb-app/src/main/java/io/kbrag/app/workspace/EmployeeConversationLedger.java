package io.kbrag.app.workspace;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.HashUtil;
import io.kbrag.domain.entity.EmployeeConversation;
import io.kbrag.domain.entity.EmployeeConversationRun;
import io.kbrag.domain.mapper.EmployeeConversationMapper;
import io.kbrag.domain.mapper.EmployeeConversationRunMapper;
import io.kbrag.domain.model.EmployeeConversationScope;
import io.kbrag.domain.model.EmployeeRunTarget;
import io.kbrag.domain.service.BizIdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.apache.commons.lang3.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 员工问答的持久账本。上层负责当前身份、应用及证据授权，本层固定资源归属并保证事务一致性。
 * 所有方法只访问数据库；模型、网络连接和事件推送必须在调用本层并提交之后进行。
 */
@Service
@RequiredArgsConstructor
public class EmployeeConversationLedger {
    private final EmployeeConversationMapper conversations;
    private final EmployeeConversationRunMapper runs;
    private final BizIdGenerator ids;

    /** 创建已授权用户自己的会话。 */
    @Transactional
    public EmployeeConversation create(EmployeeConversationScope scope, String title) {
        var conversation = EmployeeConversation.create(ids.conversationId(), scope, title, LocalDateTime.now());
        requireWritten(conversations.insert(conversation));
        return conversation;
    }

    /**
     * 先检查幂等请求，再占据活动位置。返回 created=false 的调用方不得重复调度模型。
     * 数据库唯一键作为最终约束，两个标签页通过同一会话锁决定唯一接受者。
     */
    @Transactional
    public AcceptedRun accept(EmployeeConversationScope scope, String conversationId, String requestId,
                               String question, EmployeeRunTarget target) {
        return acceptResolved(scope, conversationId, requestId, question, () -> target);
    }

    /** 幂等检查成功后才查询当前发布版本；重放原请求不受后续版本变化影响。 */
    @Transactional
    public AcceptedRun acceptCurrent(EmployeeConversationScope scope, String conversationId, String requestId,
                                      String question, Supplier<EmployeeRunTarget> currentTarget) {
        return acceptResolved(scope, conversationId, requestId, question, currentTarget);
    }

    private AcceptedRun acceptResolved(EmployeeConversationScope scope, String conversationId, String requestId,
                                        String question, Supplier<EmployeeRunTarget> currentTarget) {
        EmployeeConversation conversation = lockOwned(scope, conversationId);
        String hash = HashUtil.sha256Hex(question);
        EmployeeConversationRun duplicate = runs.selectOne(runQuery(scope, conversationId)
                .eq(EmployeeConversationRun::getClientRequestId, requestId));
        if (duplicate != null) {
            if (!hash.equals(duplicate.getPayloadHash())) {
                throw new BizException(ErrorCode.CONVERSATION_REQUEST_CONFLICT,
                        "request id was already used with different content");
            }
            return new AcceptedRun(duplicate, false);
        }
        EmployeeRunTarget target = currentTarget.get();
        if (!scope.appId().equals(target.appId())) throw BizException.notFound("application not found");
        LocalDateTime now = LocalDateTime.now();
        String runId = ids.conversationRunId();
        int turn = conversation.beginRun(runId, now);
        var run = EmployeeConversationRun.pending(runId, conversation, scope, requestId, hash, question, target, turn);
        requireWritten(runs.insert(run));
        saveConversation(conversation, now);
        return new AcceptedRun(run, true);
    }

    /** 会话摘要分页，所有筛选在数据库完成，特殊字符按字面搜索。 */
    @Transactional(readOnly = true)
    public IPage<EmployeeConversation> list(EmployeeConversationScope scope, String keyword, long page, long size) {
        String pattern = StringUtils.isBlank(keyword) ? null
                : "%" + keyword.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        return conversations.listOwned(new Page<>(page, size), scope, pattern);
    }

    /** 读取当前归属下的会话元数据，不包含问题或答案。 */
    @Transactional(readOnly = true)
    public EmployeeConversation conversation(EmployeeConversationScope scope, String conversationId) {
        EmployeeConversation conversation = conversations.selectOne(new LambdaQueryWrapper<EmployeeConversation>()
                .eq(EmployeeConversation::getTenantId, scope.tenantId())
                .eq(EmployeeConversation::getUserId, scope.userId()).eq(EmployeeConversation::getAppId, scope.appId())
                .eq(EmployeeConversation::getConversationId, conversationId));
        if (conversation == null) throw BizException.notFound("conversation not found");
        return conversation;
    }

    /** 标题为独立字段更新，不用客户端旧会话对象覆盖活动运行或轮次。 */
    @Transactional
    public EmployeeConversation rename(EmployeeConversationScope scope, String conversationId, String title) {
        EmployeeConversation conversation = lockOwned(scope, conversationId);
        conversation.setTitle(title);
        saveConversation(conversation, LocalDateTime.now());
        return conversation;
    }

    /** 先终止活动运行再软删除会话，两步在同一事务内完成，返回需取消的上游标识。 */
    @Transactional
    public String delete(EmployeeConversationScope scope, String conversationId) {
        EmployeeConversation conversation = lockOwned(scope, conversationId);
        String activeRun = conversation.getActiveRunId();
        if (activeRun != null) {
            EmployeeConversationRun run = requireRun(scope, conversationId, activeRun);
            if (run.cancel(LocalDateTime.now())) {
                run.setUpdatedAt(LocalDateTime.now());
                requireWritten(runs.updateById(run));
            }
        }
        requireWritten(conversations.deleteById(conversation));
        return activeRun;
    }

    /** 内部读取未脱敏运行；HTTP 返回和模型历史构建必须另经当前证据权限过滤。 */
    @Transactional(readOnly = true)
    public EmployeeConversationRun get(EmployeeConversationScope scope, String conversationId, String runId) {
        requireOwned(scope, conversationId);
        return requireRun(scope, conversationId, runId);
    }

    /** 内部历史读取按轮次倒序分页，所有内容仍需通过证据权限过滤后才可使用。 */
    @Transactional(readOnly = true)
    public List<EmployeeConversationRun> history(EmployeeConversationScope scope, String conversationId,
                                                 int beforeTurn, int limit) {
        requireOwned(scope, conversationId);
        return runs.selectPage(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, limit, false),
                runQuery(scope, conversationId).lt(EmployeeConversationRun::getTurnNo, beforeTurn)
                        .orderByDesc(EmployeeConversationRun::getTurnNo)).getRecords();
    }

    /** 一次性领取。即使消息被重复投递，只有成功的调用方可以执行模型。 */
    @Transactional
    public boolean start(EmployeeConversationScope scope, String conversationId, String runId, String workerId) {
        return mutate(scope, conversationId, runId, run -> run.start(workerId, LocalDateTime.now()));
    }

    /** 证据先于生成正文保存；引用内容由检索及权限服务构建，不能来自浏览器。 */
    @Transactional
    public boolean retrieved(EmployeeConversationScope scope, String conversationId, String runId,
                              String workerId, String references, String semantics, boolean degraded) {
        return mutate(scope, conversationId, runId,
                run -> run.retrieved(workerId, references, semantics, degraded));
    }

    /** 保存一个累计正文检查点，连接重订阅只需读取最近一次提交。 */
    @Transactional
    public boolean checkpoint(EmployeeConversationScope scope, String conversationId, String runId,
                               String workerId, long sequence, String content) {
        return mutate(scope, conversationId, runId, run -> run.checkpoint(workerId, sequence, content));
    }

    /** 心跳只延长仍归属执行者的运行；不会复活停止或中断的任务。 */
    @Transactional
    public boolean heartbeat(EmployeeConversationScope scope, String conversationId, String runId, String workerId) {
        return mutate(scope, conversationId, runId, run -> run.ownedBy(workerId));
    }

    /** 完整正文、证据和成功终态原子提交；本方法返回成功之后才能发出 done。 */
    @Transactional
    public boolean succeed(EmployeeConversationScope scope, String conversationId, String runId,
                            String workerId, long sequence, String content) {
        return mutate(scope, conversationId, runId,
                run -> run.succeed(workerId, sequence, content, LocalDateTime.now()));
    }

    /** 保存执行失败及已生成的部分内容，保留重新发起新运行的条件。 */
    @Transactional
    public boolean fail(EmployeeConversationScope scope, String conversationId, String runId, String workerId,
                         String errorCode, String safeMessage) {
        return mutate(scope, conversationId, runId,
                run -> run.fail(workerId, errorCode, safeMessage, LocalDateTime.now()));
    }

    /** 队列拒绝不能遗留永远排队的活动位置。 */
    @Transactional
    public boolean reject(EmployeeConversationScope scope, String conversationId, String runId,
                           String errorCode, String safeMessage) {
        return mutate(scope, conversationId, runId,
                run -> run.reject(errorCode, safeMessage, LocalDateTime.now()));
    }

    /** 用户明确停止先持久化终态，上层随后取消上游，不依赖浏览器连接仍然存在。 */
    @Transactional
    public boolean cancel(EmployeeConversationScope scope, String conversationId, String runId) {
        return mutate(scope, conversationId, runId, run -> run.cancel(LocalDateTime.now()));
    }

    /** 扫描与执行之间可能已有心跳，取锁后重验期限避免误中断。 */
    @Transactional
    public boolean interruptIfStale(EmployeeConversationScope scope, String conversationId, String runId,
                                     LocalDateTime staleBefore) {
        return mutate(scope, conversationId, runId,
                run -> run.interruptIfStale(staleBefore, LocalDateTime.now()));
    }

    /** 正常停机中断本实例持有的运行；崩溃后的旧运行由超时补偿处理。 */
    @Transactional
    public boolean interruptOwned(EmployeeConversationScope scope, String conversationId, String runId, String workerId) {
        return mutate(scope, conversationId, runId, run -> run.interruptOwned(workerId, LocalDateTime.now()));
    }

    private boolean mutate(EmployeeConversationScope scope, String conversationId, String runId,
                             Predicate<EmployeeConversationRun> mutation) {
        EmployeeConversation conversation = lockOwned(scope, conversationId);
        EmployeeConversationRun run = requireRun(scope, conversationId, runId);
        if (!mutation.test(run)) return false;
        LocalDateTime now = LocalDateTime.now();
        // 已加载的审计字段不一定由填充器覆盖，显式更新保证心跳和过期判定一致。
        run.setUpdatedAt(now);
        requireWritten(runs.updateById(run));
        if (run.getStatus().terminal()) {
            conversation.finishRun(runId, now);
            saveConversation(conversation, now);
        }
        return true;
    }

    private void saveConversation(EmployeeConversation conversation, LocalDateTime now) {
        conversation.setUpdatedAt(now);
        requireWritten(conversations.updateById(conversation));
    }

    private EmployeeConversation lockOwned(EmployeeConversationScope scope, String id) {
        EmployeeConversation conversation = conversations.lockOwned(scope, id);
        if (conversation == null) throw BizException.notFound("conversation not found");
        return conversation;
    }

    private void requireOwned(EmployeeConversationScope scope, String id) {
        Long count = conversations.selectCount(new LambdaQueryWrapper<EmployeeConversation>()
                .eq(EmployeeConversation::getTenantId, scope.tenantId())
                .eq(EmployeeConversation::getUserId, scope.userId()).eq(EmployeeConversation::getAppId, scope.appId())
                .eq(EmployeeConversation::getConversationId, id));
        if (count == 0) throw BizException.notFound("conversation not found");
    }

    private EmployeeConversationRun requireRun(EmployeeConversationScope scope, String conversationId, String runId) {
        EmployeeConversationRun run = runs.selectOne(runQuery(scope, conversationId)
                .eq(EmployeeConversationRun::getRunId, runId));
        if (run == null) throw BizException.notFound("conversation run not found");
        return run;
    }

    private LambdaQueryWrapper<EmployeeConversationRun> runQuery(EmployeeConversationScope scope, String id) {
        return new LambdaQueryWrapper<EmployeeConversationRun>()
                .eq(EmployeeConversationRun::getTenantId, scope.tenantId())
                .eq(EmployeeConversationRun::getUserId, scope.userId()).eq(EmployeeConversationRun::getAppId, scope.appId())
                .eq(EmployeeConversationRun::getConversationId, id);
    }

    private void requireWritten(int count) {
        if (count != 1) throw new BizException(ErrorCode.INTERNAL_ERROR, "conversation state was not saved");
    }

    /** 只对 created=true 的返回结果执行调度，重复请求返回原运行。 */
    public record AcceptedRun(EmployeeConversationRun run, boolean created) {
    }
}
