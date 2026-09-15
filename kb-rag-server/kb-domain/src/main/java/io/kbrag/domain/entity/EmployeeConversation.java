package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.model.EmployeeConversationScope;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 员工私有会话聚合根；活动运行与轮次在同一行锁内推进。 */
@Getter
@Setter
@TableName("t_kb_conversation")
public class EmployeeConversation extends BaseEntity {
    private static final long serialVersionUID = 1L;

    private String conversationId;
    private String tenantId;
    private String userId;
    private String appId;
    private String title;
    private Integer lastTurn;
    private LocalDateTime lastActivityAt;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String activeRunId;

    /** 创建空会话，所有归属来自通过授权的服务端身份。 */
    public static EmployeeConversation create(String id, EmployeeConversationScope scope,
                                               String title, LocalDateTime now) {
        EmployeeConversation conversation = new EmployeeConversation();
        conversation.conversationId = id;
        conversation.tenantId = scope.tenantId();
        conversation.userId = scope.userId();
        conversation.appId = scope.appId();
        conversation.title = title;
        conversation.lastTurn = 0;
        conversation.lastActivityAt = now;
        return conversation;
    }

    /** 占据唯一活动运行位置，同时分配单调递增的轮次。 */
    public int beginRun(String runId, LocalDateTime now) {
        if (activeRunId != null) {
            throw new BizException(ErrorCode.CONVERSATION_BUSY, "conversation already has an active run");
        }
        activeRunId = runId;
        lastActivityAt = now;
        return ++lastTurn;
    }

    /** 只释放当前运行，晚到的旧运行不能清除新运行的位置。 */
    public void finishRun(String runId, LocalDateTime now) {
        if (runId.equals(activeRunId)) {
            activeRunId = null;
            lastActivityAt = now;
        }
    }
}
