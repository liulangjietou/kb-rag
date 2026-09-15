package io.kbrag.domain.entity;

import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.enums.ConversationRunStatus;
import io.kbrag.domain.enums.FeedbackVerdict;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 反馈是对完整回答的最新评价，幂等与冲突规则在领域对象中保持一致。 */
class EmployeeAnswerFeedbackTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 11, 10, 0);

    @ParameterizedTest
    @EnumSource(value = ConversationRunStatus.class, names = "SUCCEEDED", mode = EnumSource.Mode.EXCLUDE)
    void shouldRejectEveryIncompleteStatus(ConversationRunStatus status) {
        var run = run(status);
        assertThrows(BizException.class, () -> run.giveFeedback(FeedbackVerdict.BAD, "回答不完整", 5, NOW));
        assertNull(run.feedback());
    }

    @Test
    void shouldReplaySameFeedbackWithoutChangingItsTimestamp() {
        var run = run(ConversationRunStatus.SUCCEEDED);
        assertTrue(run.giveFeedback(FeedbackVerdict.BAD, "需要依据", 5, NOW));
        run.setLockVersion(6);
        assertFalse(run.giveFeedback(FeedbackVerdict.BAD, "需要依据", 5, NOW.plusMinutes(1)));
        assertEquals(NOW, run.feedback().updatedAt());
        var error = assertThrows(BizException.class, () -> run.giveFeedback(FeedbackVerdict.GOOD, null, 5, NOW));
        assertEquals(ErrorCode.FEEDBACK_VERSION_CONFLICT, error.getErrorCode());
        assertTrue(run.giveFeedback(FeedbackVerdict.GOOD, null, 6, NOW.plusMinutes(2)));
        assertEquals(FeedbackVerdict.GOOD, run.feedback().verdict());
        assertNull(run.feedback().note());
    }

    private EmployeeConversationRun run(ConversationRunStatus status) {
        var run = new EmployeeConversationRun();
        run.setStatus(status);
        run.setLockVersion(5);
        return run;
    }
}
