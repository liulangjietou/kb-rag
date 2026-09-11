package io.kbrag.domain.enums;

/** 问答运行终态不可逆；重试必须创建新的运行。 */
public enum ConversationRunStatus {
    PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED, INTERRUPTED;

    /** 是否已结束，包括失败、主动停止和进程中断。 */
    public boolean terminal() {
        return this != PENDING && this != RUNNING;
    }
}
