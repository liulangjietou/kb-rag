package io.kbrag.domain.enums;

/** 阶段只描述实际完成的工作；终态由运行状态表示。 */
public enum ConversationRunStage {
    QUEUED, RETRIEVING, GENERATING, FINISHED
}
