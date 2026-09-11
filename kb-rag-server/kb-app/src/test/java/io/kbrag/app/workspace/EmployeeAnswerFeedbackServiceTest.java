package io.kbrag.app.workspace;

import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.entity.EmployeeConversationRun;
import io.kbrag.domain.enums.ConversationRunStage;
import io.kbrag.domain.enums.ConversationRunStatus;
import io.kbrag.domain.enums.FeedbackVerdict;
import io.kbrag.domain.enums.UserSource;
import io.kbrag.domain.model.EmployeeConversationScope;
import io.kbrag.domain.model.EmployeeRunTarget;
import io.kbrag.domain.model.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** 当前证据授权同时保护评价说明，反馈不会触发执行器或进入多轮上下文。 */
class EmployeeAnswerFeedbackServiceTest {
    private final UserPrincipal user = new UserPrincipal("user", "tenant", "employee", "员工", UserSource.LOCAL,
            Set.of(), Set.of(), Set.of("app:use"), true, Set.of());
    private final EmployeeConversationScope scope = new EmployeeConversationScope("tenant", "user", "app");
    private final EmployeeWorkspaceAccess access = mock(EmployeeWorkspaceAccess.class);
    private final EmployeeConversationLedger ledger = mock(EmployeeConversationLedger.class);
    private final EmployeeEvidenceService evidence = mock(EmployeeEvidenceService.class);
    private final EmployeeRunCoordinator coordinator = mock(EmployeeRunCoordinator.class);
    private final EmployeeConversationHistory history = new EmployeeConversationHistory(ledger, evidence);
    private final EmployeeConversationService service = new EmployeeConversationService(access, ledger, history, coordinator);
    private EmployeeConversationRun run;

    @BeforeEach
    void setUp() {
        run = new EmployeeConversationRun();
        run.setRunId("run"); run.setConversationId("conv"); run.setTurnNo(1); run.setLockVersion(6);
        run.setCheckpointSeq(1L); run.setDegraded(false); run.setQuestion("问题"); run.setAnswer("回答");
        run.setStatus(ConversationRunStatus.SUCCEEDED); run.setStage(ConversationRunStage.FINISHED);
        run.setReferencesJson("[]");
        run.setTargetJson(JsonUtil.toJson(new EmployeeRunTarget("app", "av", "v1", "{}", null, null, false)));
        run.setFeedbackVerdict(FeedbackVerdict.BAD); run.setFeedbackNote("反馈中引用了原答案内容");
        run.setFeedbackUpdatedAt(LocalDateTime.now());
        when(access.current()).thenReturn(user);
        when(access.scope(user, "app")).thenReturn(scope);
        when(ledger.get(scope, "conv", "run")).thenReturn(run);
    }

    @Test
    void shouldHideSavedFeedbackAndRejectWritesWhenEvidenceIsRevoked() {
        when(evidence.canReadAll(any(), anyList())).thenReturn(false);
        var view = history.present(user, run);
        assertTrue(view.restricted());
        assertNull(view.feedback());
        assertFalse(JsonUtil.toJson(view).contains("反馈中引用了原答案内容"));
        assertThrows(BizException.class, () -> service.feedback("app", "conv", "run", FeedbackVerdict.BAD, "补充", 6));
        verify(ledger).get(scope, "conv", "run");
        verifyNoMoreInteractions(ledger);
        verifyNoInteractions(coordinator);
    }

    @Test
    void shouldReturnTheSavedFeedbackWithoutCallingTheModelCoordinator() {
        when(evidence.canReadAll(any(), anyList())).thenReturn(true);
        when(ledger.feedback(scope, "conv", "run", FeedbackVerdict.BAD, run.getFeedbackNote(), 5)).thenReturn(run);
        var view = service.feedback("app", "conv", "run", FeedbackVerdict.BAD, run.getFeedbackNote(), 5);
        assertEquals(run.feedback(), view.feedback());
        verify(access).current();
        verify(ledger).feedback(scope, "conv", "run", FeedbackVerdict.BAD, run.getFeedbackNote(), 5);
        verifyNoInteractions(coordinator);
    }

    @Test
    void shouldKeepFeedbackOutOfModelConversationHistory() {
        when(evidence.canReadAll(any(), anyList())).thenReturn(true);
        when(ledger.history(scope, "conv", 2, 10)).thenReturn(List.of(run));
        var context = history.modelContext(user, scope, "conv", 2);
        assertEquals(List.of("问题", "回答"), context.messages().stream().map(message -> message.getContent()).toList());
        assertFalse(JsonUtil.toJson(context).contains(run.getFeedbackNote()));
    }
}
