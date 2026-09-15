package io.kbrag.domain.model;

/** 已通过员工入口授权的会话归属；数据库操作始终同时限定租户、用户与应用。 */
public record EmployeeConversationScope(String tenantId, String userId, String appId) {
}
