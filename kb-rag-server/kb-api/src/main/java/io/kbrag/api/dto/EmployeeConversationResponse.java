package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.domain.entity.EmployeeConversation;

import java.time.LocalDateTime;

/** 员工自己的会话摘要，不暴露租户、用户、数据库主键等内部字段。 */
public record EmployeeConversationResponse(
        @JsonProperty("conversation_id") String conversationId,
        @JsonProperty("app_id") String appId, String title,
        @JsonProperty("last_turn") int lastTurn,
        @JsonProperty("active_run_id") String activeRunId,
        @JsonProperty("last_activity_at") LocalDateTime lastActivityAt,
        @JsonProperty("created_at") LocalDateTime createdAt) {

    /** 从已校验归属的会话中提取展示字段。 */
    public static EmployeeConversationResponse from(EmployeeConversation conversation) {
        return new EmployeeConversationResponse(conversation.getConversationId(), conversation.getAppId(),
                conversation.getTitle(), conversation.getLastTurn(), conversation.getActiveRunId(),
                conversation.getLastActivityAt(), conversation.getCreatedAt());
    }
}
