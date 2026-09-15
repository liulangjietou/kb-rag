package io.kbrag.domain.enums;

/** 管理首页可定位的处理事项；不能用任意客户端字符串拼装查询。 */
public enum KnowledgeTodoKind {
    PENDING_CONFIRM,
    PENDING_REVIEW,
    WEB_SOURCE_FAILED,
    EXT_SOURCE_ATTENTION
}
