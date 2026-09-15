package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.app.workspace.EmployeeHomeService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 首页只含当前可用入口与本人会话摘要，不包含配置、证据和内部归属标识。 */
public record EmployeeHomeResponse(List<ApplicationItem> applications,
        @JsonProperty("recent_conversations") List<RecentConversation> recentConversations) {

    /** 显式列出允许返回的字段，避免领域实体自动序列化泄漏内部内容。 */
    public static EmployeeHomeResponse from(EmployeeHomeService.Overview overview) {
        Map<String, String> names = overview.applications().stream().collect(Collectors.toMap(
                item -> item.app().getAppId(), item -> item.app().getName()));
        return new EmployeeHomeResponse(overview.applications().stream().map(item -> new ApplicationItem(
                item.app().getAppId(), item.app().getName(), item.app().getDescription(),
                item.version().getAppVersionId(), item.version().getVersion())).toList(),
                overview.recentConversations().stream().map(conversation -> new RecentConversation(
                        conversation.getConversationId(), conversation.getAppId(), names.get(conversation.getAppId()),
                        conversation.getTitle(), conversation.getActiveRunId(), conversation.getLastActivityAt())).toList());
    }

    /** 与员工目录相同的五项使用元数据。 */
    public record ApplicationItem(@JsonProperty("app_id") String appId, String name, String description,
            @JsonProperty("released_version_id") String releasedVersionId,
            @JsonProperty("released_version") String releasedVersion) { }

    /** 活动时间是会话真实更新时间，不将文档创建时间解释为访问时间。 */
    public record RecentConversation(@JsonProperty("conversation_id") String conversationId,
            @JsonProperty("app_id") String appId, @JsonProperty("app_name") String appName, String title,
            @JsonProperty("active_run_id") String activeRunId,
            @JsonProperty("last_activity_at") LocalDateTime lastActivityAt) { }
}
