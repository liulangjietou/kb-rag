package io.kbrag.domain.model;

import io.kbrag.domain.enums.FeedbackVerdict;

import java.time.LocalDateTime;

/** 针对整轮回答的最新评价，不将任意被引用分片当作正确证据。 */
public record EmployeeAnswerFeedback(FeedbackVerdict verdict, String note, LocalDateTime updatedAt) { }
