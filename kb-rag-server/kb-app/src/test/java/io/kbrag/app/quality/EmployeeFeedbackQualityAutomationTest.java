package io.kbrag.app.quality;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.entity.EmployeeConversationRun;
import io.kbrag.domain.enums.FeedbackVerdict;
import io.kbrag.domain.model.AppConfigSnapshot;
import io.kbrag.domain.model.EmployeeRunTarget;
import io.kbrag.domain.model.KbRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

/** 差评自动建单开关与各知识库扇出。 */
class EmployeeFeedbackQualityAutomationTest {

    private final QualityAutomationConfigService configService = mock(QualityAutomationConfigService.class);
    private final EmployeeQualityIssueCreator issueCreator = mock(EmployeeQualityIssueCreator.class);
    private final EmployeeFeedbackQualityAutomation automation =
            new EmployeeFeedbackQualityAutomation(configService, issueCreator);
    private EmployeeConversationRun run;
    private ListAppender<ILoggingEvent> logWatcher;

    @BeforeEach
    void attachLogWatcher() {
        logWatcher = new ListAppender<>();
        logWatcher.start();
        ((Logger) LoggerFactory.getLogger(EmployeeFeedbackQualityAutomation.class)).addAppender(logWatcher);
    }

    @AfterEach
    void detachLogWatcher() {
        ((Logger) LoggerFactory.getLogger(EmployeeFeedbackQualityAutomation.class)).detachAppender(logWatcher);
    }

    @BeforeEach
    void setUp() {
        var snapshot = new AppConfigSnapshot();
        snapshot.setKbRefs(List.of(KbRef.of("kb-a"), KbRef.of("kb-b")));
        var target = new EmployeeRunTarget(
                "app-1", "av-1", "V1", JsonUtil.toJson(snapshot), null, null, true);
        run = new EmployeeConversationRun();
        run.setRunId("run-1");
        run.setFeedbackVerdict(FeedbackVerdict.BAD);
        run.setTargetJson(JsonUtil.toJson(target));
    }

    @Test
    void shouldSkipWhenVerdictIsNotBad() {
        run.setFeedbackVerdict(FeedbackVerdict.GOOD);
        when(configService.autoIssueFromEmployeeBadEnabled()).thenReturn(true);

        automation.onFeedbackSaved(run);

        verifyNoInteractions(issueCreator);
    }

    @Test
    void shouldSkipWhenAutomationSwitchIsOff() {
        when(configService.autoIssueFromEmployeeBadEnabled()).thenReturn(false);

        automation.onFeedbackSaved(run);

        verifyNoInteractions(issueCreator);
    }

    @Test
    void shouldCreateOneIssuePerLinkedKbWhenEnabled() {
        when(configService.autoIssueFromEmployeeBadEnabled()).thenReturn(true);

        automation.onFeedbackSaved(run);

        verify(issueCreator).createIfAbsent(run, "kb-a", "av-1");
        verify(issueCreator).createIfAbsent(run, "kb-b", "av-1");
        verifyNoMoreInteractions(issueCreator);
    }

    @Test
    void shouldContinueOtherKbsWhenOneCreationFails() {
        when(configService.autoIssueFromEmployeeBadEnabled()).thenReturn(true);
        doThrow(new RuntimeException("kb-a blocked"))
                .when(issueCreator).createIfAbsent(run, "kb-a", "av-1");

        automation.onFeedbackSaved(run);

        verify(issueCreator).createIfAbsent(run, "kb-a", "av-1");
        verify(issueCreator).createIfAbsent(run, "kb-b", "av-1");
    }

    /** 自动建单失败属于吞异常后继续的场景：必须以 error 级别留下带错误码、定位参数与异常栈的日志，且不得使用 warn。 */
    @Test
    void shouldLogErrorWithStackTraceWhenCreationFails() {
        when(configService.autoIssueFromEmployeeBadEnabled()).thenReturn(true);
        doThrow(new RuntimeException("kb-a blocked"))
                .when(issueCreator).createIfAbsent(run, "kb-a", "av-1");

        automation.onFeedbackSaved(run);

        assertTrue(logWatcher.list.stream().noneMatch(event -> event.getLevel() == Level.WARN));
        List<ILoggingEvent> errors = logWatcher.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR).toList();
        assertEquals(1, errors.size());
        ILoggingEvent error = errors.get(0);
        String message = error.getFormattedMessage();
        assertTrue(message.contains("errorCode=" + ErrorCode.INTERNAL_ERROR));
        assertTrue(message.contains("kbId=kb-a"));
        assertTrue(message.contains("runId=run-1"));
        assertNotNull(error.getThrowableProxy());
    }
}