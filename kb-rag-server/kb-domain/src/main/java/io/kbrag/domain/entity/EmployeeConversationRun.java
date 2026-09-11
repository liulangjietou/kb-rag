package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.enums.ConversationRunStage;
import io.kbrag.domain.enums.ConversationRunStatus;
import io.kbrag.domain.enums.FeedbackVerdict;
import io.kbrag.domain.model.EmployeeAnswerFeedback;
import io.kbrag.domain.model.EmployeeConversationScope;
import io.kbrag.domain.model.EmployeeRunTarget;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Objects;

/** 一轮持久问题、回答与证据；不记录逐 token 事件，不自动重新调用模型。 */
@Getter
@Setter
@TableName("t_kb_conversation_run")
public class EmployeeConversationRun extends BaseEntity {
    private static final long serialVersionUID = 1L;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String runId;
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String conversationId;
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String tenantId;
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String userId;
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String appId;
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String clientRequestId;
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String payloadHash;
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer turnNo;
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String question;
    private String answer;
    private String referencesJson;
    private String readSemanticsJson;
    /** 配置快照可能包含大量冻结版本标识，不随心跳或正文检查点重复写入。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String targetJson;
    private ConversationRunStatus status;
    private ConversationRunStage stage;
    private String workerId;
    private Long checkpointSeq;
    private Boolean degraded;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private FeedbackVerdict feedbackVerdict;
    private String feedbackNote;
    private LocalDateTime feedbackUpdatedAt;

    /** 只评价已保存的完整回答；相同内容的网络重试幂等，旧版本不能覆盖不同的新评价。 */
    public boolean giveFeedback(FeedbackVerdict verdict, String note, int expectedRevision, LocalDateTime now) {
        if (status != ConversationRunStatus.SUCCEEDED) {
            throw BizException.invalidParam("回答尚未完整保存，暂时不能评价");
        }
        if (feedbackVerdict == verdict && Objects.equals(feedbackNote, note)) return false;
        if (getLockVersion() != expectedRevision) {
            throw new BizException(ErrorCode.FEEDBACK_VERSION_CONFLICT, "这条反馈已在其他页面更新，请重新读取后再修改");
        }
        feedbackVerdict = verdict;
        feedbackNote = note;
        feedbackUpdatedAt = now;
        return true;
    }

    /** 返回已记录的最新评价，未评价时为空。 */
    public EmployeeAnswerFeedback feedback() {
        return feedbackVerdict == null ? null : new EmployeeAnswerFeedback(feedbackVerdict, feedbackNote, feedbackUpdatedAt);
    }

    /** 接受后即可读取原问题及所选配置；只有事务提交后才允许调度。 */
    public static EmployeeConversationRun pending(String id, EmployeeConversation conversation,
                                                  EmployeeConversationScope scope, String requestId,
                                                  String payloadHash, String question,
                                                  EmployeeRunTarget target, int turn) {
        EmployeeConversationRun run = new EmployeeConversationRun();
        run.runId = id;
        run.conversationId = conversation.getConversationId();
        run.tenantId = scope.tenantId();
        run.userId = scope.userId();
        run.appId = scope.appId();
        run.clientRequestId = requestId;
        run.payloadHash = payloadHash;
        run.turnNo = turn;
        run.question = question;
        run.answer = "";
        run.referencesJson = "[]";
        run.readSemanticsJson = "{}";
        run.targetJson = JsonUtil.toJson(target);
        run.status = ConversationRunStatus.PENDING;
        run.stage = ConversationRunStage.QUEUED;
        run.checkpointSeq = 0L;
        run.degraded = false;
        return run;
    }

    /** 只有第一次领取成功；相同执行者重复领取也不能再次调用模型。 */
    public boolean start(String owner, LocalDateTime now) {
        if (status != ConversationRunStatus.PENDING) return false;
        workerId = owner;
        startedAt = now;
        status = ConversationRunStatus.RUNNING;
        stage = ConversationRunStage.RETRIEVING;
        return true;
    }

    /** 生成前持久化已过滤的证据；之后读历史和推送正文都据此重新校验权限。 */
    public boolean retrieved(String owner, String references, String semantics, boolean degraded) {
        if (!ownedBy(owner) || stage != ConversationRunStage.RETRIEVING) return false;
        referencesJson = references;
        readSemanticsJson = semantics;
        this.degraded = degraded;
        stage = ConversationRunStage.GENERATING;
        return true;
    }

    /** 保存累计正文而非单个增量，旧序号和终态后的检查点均不生效。 */
    public boolean checkpoint(String owner, long sequence, String content) {
        if (!ownedBy(owner) || stage != ConversationRunStage.GENERATING || sequence <= checkpointSeq) {
            return false;
        }
        checkpointSeq = sequence;
        answer = content;
        return true;
    }

    /** 答案与证据须由同一事务保存成功后，应用层才可通知完成。 */
    public boolean succeed(String owner, long sequence, String content, LocalDateTime now) {
        if (!checkpoint(owner, sequence, content)) return false;
        finish(ConversationRunStatus.SUCCEEDED, now);
        return true;
    }

    /** 运行失败只能由实际执行者报告，错误正文由入口映射为安全文案。 */
    public boolean fail(String owner, String code, String message, LocalDateTime now) {
        if (!ownedBy(owner)) return false;
        errorCode = code;
        errorMessage = message;
        finish(ConversationRunStatus.FAILED, now);
        return true;
    }

    /** 调度拒绝发生在开始之前，不可覆盖已经执行的运行。 */
    public boolean reject(String code, String message, LocalDateTime now) {
        if (status != ConversationRunStatus.PENDING) return false;
        errorCode = code;
        errorMessage = message;
        finish(ConversationRunStatus.FAILED, now);
        return true;
    }

    /** 明确停止保持现有正文和证据，后续模型回调无权改写。 */
    public boolean cancel(LocalDateTime now) {
        if (status.terminal()) return false;
        finish(ConversationRunStatus.CANCELLED, now);
        return true;
    }

    /** 仅补偿过期运行，不接管或重新生成；正在心跳的运行不会被中断。 */
    public boolean interruptIfStale(LocalDateTime staleBefore, LocalDateTime now) {
        if (status.terminal() || !getUpdatedAt().isBefore(staleBefore)) return false;
        finish(ConversationRunStatus.INTERRUPTED, now);
        return true;
    }

    /** 进程正常退出时仅中断本执行者或尚未领取的运行，不覆盖其他执行者的工作。 */
    public boolean interruptOwned(String owner, LocalDateTime now) {
        if (status != ConversationRunStatus.PENDING && !ownedBy(owner)) return false;
        finish(ConversationRunStatus.INTERRUPTED, now);
        return true;
    }

    /** 判定执行者是否仍拥有运行；终态即撤销所有写入权。 */
    public boolean ownedBy(String owner) {
        return status == ConversationRunStatus.RUNNING && Objects.equals(workerId, owner);
    }

    private void finish(ConversationRunStatus terminal, LocalDateTime now) {
        status = terminal;
        stage = ConversationRunStage.FINISHED;
        finishedAt = now;
    }
}
