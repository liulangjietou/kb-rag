package io.kbrag.domain.enums;

/** 人工核实的问题原因，不由检索分数自动推断。 */
public enum QualityIssueReason { MISSING_KNOWLEDGE, OUTDATED_KNOWLEDGE, RETRIEVAL_MISS, ANSWER_INCORRECT, QUESTION_UNCLEAR, OTHER }
