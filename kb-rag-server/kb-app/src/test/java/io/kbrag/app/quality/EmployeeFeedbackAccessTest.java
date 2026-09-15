package io.kbrag.app.quality;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.app.support.MybatisLambdaCache;
import io.kbrag.app.workspace.EmployeeEvidenceService;
import io.kbrag.app.workspace.EmployeeWorkspaceAccess;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.constant.PermissionCodes;
import io.kbrag.domain.context.UserContextHolder;
import io.kbrag.domain.entity.EmployeeConversation;
import io.kbrag.domain.entity.EmployeeConversationRun;
import io.kbrag.domain.entity.KnowledgeQualityIssue;
import io.kbrag.domain.enums.ConversationRunStatus;
import io.kbrag.domain.enums.FeedbackVerdict;
import io.kbrag.domain.enums.UserSource;
import io.kbrag.domain.mapper.EmployeeConversationMapper;
import io.kbrag.domain.mapper.EmployeeConversationRunMapper;
import io.kbrag.domain.mapper.KnowledgeQualityIssueMapper;
import io.kbrag.domain.model.EmployeeCitation;
import io.kbrag.domain.model.EmployeeRunTarget;
import io.kbrag.domain.model.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 整轮反馈的权限、来源范围与反馈撤回边界。 */
class EmployeeFeedbackAccessTest {
    private final EmployeeConversationRunMapper runs = mock(EmployeeConversationRunMapper.class);
    private final EmployeeConversationMapper conversations = mock(EmployeeConversationMapper.class);
    private final EmployeeWorkspaceAccess workspace = mock(EmployeeWorkspaceAccess.class);
    private final EmployeeEvidenceService evidence = mock(EmployeeEvidenceService.class);
    private final KnowledgeQualityIssueMapper issues = mock(KnowledgeQualityIssueMapper.class);
    private final EmployeeFeedbackAccess access = new EmployeeFeedbackAccess(runs, conversations, workspace, evidence, issues);
    private EmployeeConversationRun run;
    private EmployeeRunTarget target;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.register(EmployeeConversation.class, EmployeeConversationRun.class, KnowledgeQualityIssue.class);
        principal(PermissionCodes.FEEDBACK_MANAGE, PermissionCodes.APP_READ);
        target = new EmployeeRunTarget("app", "version", "v1", "{\"kb_refs\":[{\"kb_id\":\"kb\"}]}", null, null, true);
        run = new EmployeeConversationRun(); run.setRunId("run"); run.setTenantId("tenant"); run.setAppId("app");
        run.setConversationId("conversation"); run.setUserId("employee"); run.setStatus(ConversationRunStatus.SUCCEEDED);
        run.setFeedbackVerdict(FeedbackVerdict.BAD); run.setQuestion("原问题"); run.setAnswer("错误回答");
        run.setTargetJson(JsonUtil.toJson(target)); run.setReferencesJson("[]");
        when(runs.selectOne(any())).thenReturn(run);
        when(conversations.selectOne(any())).thenReturn(new EmployeeConversation());
        when(evidence.canReadAll(any(), anyList())).thenReturn(true);
    }

    @AfterEach
    void clear() { UserContextHolder.clear(); }

    @Test
    void operatorNeedsFeedbackAndAppReadButDoesNotNeedEmployeeAppUse() {
        assertEquals("错误回答", access.read("kb", "run").run().getAnswer());
        verify(workspace).requireTarget(UserContextHolder.get(), target);
        for (String only : List.of(PermissionCodes.FEEDBACK_MANAGE, PermissionCodes.APP_READ, PermissionCodes.APP_USE)) {
            principal(only);
            assertEquals(ErrorCode.FORBIDDEN, assertThrows(BizException.class, () -> access.read("kb", "run")).getErrorCode());
        }
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void tenantAndConversationOwnershipAreExplicitEvenForAnOperatorReadingAnotherEmployeeFeedback() {
        access.read("kb", "run");
        ArgumentCaptor<LambdaQueryWrapper<EmployeeConversationRun>> rq = ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
        verify(runs).selectOne(rq.capture());
        assertTrue(rq.getValue().getSqlSegment().contains("tenant_id"));
        assertTrue(rq.getValue().getParamNameValuePairs().containsValue("tenant"));
        ArgumentCaptor<LambdaQueryWrapper<EmployeeConversation>> cq = ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
        verify(conversations).selectOne(cq.capture());
        assertTrue(cq.getValue().getSqlSegment().contains("user_id"));
        assertTrue(cq.getValue().getParamNameValuePairs().containsValue("employee"));
    }

    @Test
    void unrelatedKbMissingConversationAndUnratedRunAreNotReadable() {
        assertThrows(BizException.class, () -> access.read("other", "run"));
        run.setFeedbackVerdict(null);
        assertThrows(BizException.class, () -> access.read("kb", "run"));
        run.setFeedbackVerdict(FeedbackVerdict.BAD);
        when(conversations.selectOne(any())).thenReturn(null);
        assertThrows(BizException.class, () -> access.read("kb", "run"));
        verifyNoInteractions(evidence);
    }

    @Test
    void inheritedReferencesAreRequiredAndARevokedOneHidesTheWholeAnswer() {
        var inherited = new EmployeeCitation("doc", "dv", "chunk", "other-kb", "旧资料", "v1", null, null, null, null, null, "旧依据", true);
        run.setReferencesJson(JsonUtil.toJson(List.of(inherited)));
        when(evidence.canReadAll(any(), eq(List.of(inherited)))).thenReturn(false);
        assertEquals(ErrorCode.FORBIDDEN, assertThrows(BizException.class, () -> access.read("kb", "run")).getErrorCode());
        verify(evidence).canReadAll(UserContextHolder.get(), List.of(inherited));
    }

    @Test
    void currentApplicationAndKbRevocationCannotBeBypassedByEmptyReferences() {
        doThrow(BizException.forbidden("scope changed")).when(workspace).requireTarget(any(), any());
        assertThrows(BizException.class, () -> access.read("kb", "run"));
        verifyNoInteractions(evidence);
    }

    @Test
    void creationLocksConversationBeforeRereadingRunAndRejectsConcurrentFeedbackChange() {
        var changed = new EmployeeConversationRun(); changed.setStatus(ConversationRunStatus.SUCCEEDED);
        changed.setFeedbackVerdict(FeedbackVerdict.GOOD); changed.setAppId("app"); changed.setTargetJson(run.getTargetJson()); changed.setReferencesJson("[]");
        when(runs.selectOne(any())).thenReturn(run, changed);
        assertThrows(BizException.class, () -> access.lockBad("kb", "run"));
        var order = inOrder(runs, conversations);
        order.verify(runs).selectOne(any());
        order.verify(conversations).selectOne(argThat(q -> q.getSqlSegment().contains("FOR UPDATE")));
        order.verify(runs).selectOne(argThat(q -> q.getSqlSegment().contains("FOR UPDATE")));
    }

    @Test
    void helpfulOnlyAnswerCannotBeReadThroughTheNegativeFeedbackEndpoint() {
        run.setFeedbackVerdict(FeedbackVerdict.GOOD);
        assertThrows(BizException.class, () -> access.read("kb", "run"));
    }

    @Test
    void previouslyReportedIssueCanBeReviewedAfterEmployeeChangesTheirVerdict() {
        run.setFeedbackVerdict(FeedbackVerdict.GOOD);
        when(issues.selectCount(any())).thenReturn(1L);
        assertEquals(FeedbackVerdict.GOOD, access.read("kb", "run").run().getFeedbackVerdict());
        assertThrows(BizException.class, () -> access.lockBad("kb", "run"));
    }

    private void principal(String... permissions) {
        UserContextHolder.set(new UserPrincipal("operator", "tenant", "operator", "维护人", UserSource.LOCAL,
                Set.of(), Set.of(), Set.of(permissions), true, Set.of(), true, Set.of()));
    }
}
