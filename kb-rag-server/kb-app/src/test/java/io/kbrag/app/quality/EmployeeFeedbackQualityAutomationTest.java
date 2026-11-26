package io.kbrag.app.quality;

import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.entity.EmployeeConversationRun;
import io.kbrag.domain.enums.FeedbackVerdict;
import io.kbrag.domain.model.AppConfigSnapshot;
import io.kbrag.domain.model.EmployeeRunTarget;
import io.kbrag.domain.model.KbRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.*;

/** 差评自动建单开关与各知识库扇出。 */
class EmployeeFeedbackQualityAutomationTest {

    private final QualityAutomationConfigService configService = mock(QualityAutomationConfigService.class);
    private final EmployeeQualityIssueCreator issueCreator = mock(EmployeeQualityIssueCreator.class);
    private final EmployeeFeedbackQualityAutomation automation =
            new EmployeeFeedbackQualityAutomation(configService, issueCreator);
    private EmployeeConversationRun run;

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
}