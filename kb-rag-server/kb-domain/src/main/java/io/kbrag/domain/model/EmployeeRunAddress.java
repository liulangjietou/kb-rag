package io.kbrag.domain.model;

/** 过期扫描只读取定位数据，不将问题或证据带入后台扫描日志。 */
public record EmployeeRunAddress(String tenantId, String userId, String appId,
                                 String conversationId, String runId) {

    /** 恢复操作继续使用原有三重归属条件。 */
    public EmployeeConversationScope scope() {
        return new EmployeeConversationScope(tenantId, userId, appId);
    }
}
