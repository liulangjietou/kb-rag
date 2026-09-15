package io.kbrag.domain.enums;

/** 知识问题的处理阶段，解决必须经过实际回归核验。 */
public enum QualityIssueStatus { NEW, IN_PROGRESS, WAITING_REGRESSION, RESOLVED }
