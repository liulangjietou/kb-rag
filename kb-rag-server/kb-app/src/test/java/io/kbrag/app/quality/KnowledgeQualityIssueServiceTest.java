package io.kbrag.app.quality;

import io.kbrag.app.eval.EvalCaseCommand;
import io.kbrag.app.eval.EvalDatasetService;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.constant.PermissionCodes;
import io.kbrag.domain.context.UserContextHolder;
import io.kbrag.domain.entity.AppVersion;
import io.kbrag.domain.entity.EvalCase;
import io.kbrag.domain.entity.EvalDataset;
import io.kbrag.domain.entity.KnowledgeQualityIssue;
import io.kbrag.domain.entity.QualityIssueRecord;
import io.kbrag.domain.entity.RetrievalFeedback;
import io.kbrag.domain.entity.SearchInsight;
import io.kbrag.domain.enums.AnchorType;
import io.kbrag.domain.enums.CaseStatus;
import io.kbrag.domain.enums.FeedbackVerdict;
import io.kbrag.domain.enums.QualityIssueReason;
import io.kbrag.domain.enums.QualityIssueSource;
import io.kbrag.domain.enums.QualityIssueStatus;
import io.kbrag.domain.enums.UserSource;
import io.kbrag.domain.mapper.KnowledgeQualityIssueMapper;
import io.kbrag.domain.mapper.QualityIssueRecordMapper;
import io.kbrag.domain.mapper.RetrievalFeedbackMapper;
import io.kbrag.domain.mapper.SearchInsightMapper;
import io.kbrag.domain.model.UserPrincipal;
import io.kbrag.domain.service.QueryDigestFactory;
import io.kbrag.domain.service.TextDesensitizer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 来源真实性、事务写入冲突、关联用例修订和额外权限边界。 */
class KnowledgeQualityIssueServiceTest {
    private final KnowledgeQualityIssueMapper issues = mock(KnowledgeQualityIssueMapper.class);
    private final QualityIssueRecordMapper records = mock(QualityIssueRecordMapper.class);
    private final RetrievalFeedbackMapper feedback = mock(RetrievalFeedbackMapper.class);
    private final SearchInsightMapper insights = mock(SearchInsightMapper.class);
    private final EvalDatasetService datasets = mock(EvalDatasetService.class);
    private final QualityIssueAccess access = mock(QualityIssueAccess.class);
    private final QualityRegressionVerifier regression = mock(QualityRegressionVerifier.class);
    private final KnowledgeQualityIssueService service = new KnowledgeQualityIssueService(issues, records, feedback,
            insights, datasets, access, regression, new QueryDigestFactory(new TextDesensitizer()));
    private KnowledgeQualityIssue issue;

    @BeforeEach
    void setUp() {
        principal(Set.of(PermissionCodes.FEEDBACK_MANAGE, PermissionCodes.EVAL_READ, PermissionCodes.EVAL_WRITE, PermissionCodes.APP_READ));
        issue = KnowledgeQualityIssue.open("issue_safe", "kb_safe", QualityIssueSource.ZERO_HIT, "hash_safe", "问题摘要");
        issue.claim("user_safe", "处理人", 0);
        when(issues.selectOne(any())).thenReturn(issue);
        when(issues.updateById(any(KnowledgeQualityIssue.class))).thenReturn(1);
        when(issues.insert(any(KnowledgeQualityIssue.class))).thenReturn(1);
        when(records.insert(any(QualityIssueRecord.class))).thenReturn(1);
        EvalDataset dataset = new EvalDataset(); dataset.setDatasetId("ds_safe"); dataset.setKbId("kb_safe");
        when(datasets.require("ds_safe")).thenReturn(dataset);
        AppVersion version = new AppVersion(); version.setAppId("app_safe");
        when(access.requireVersion("kb_safe", "av_safe")).thenReturn(version);
        when(datasets.createCase(eq("ds_safe"), any())).thenReturn(correctedCase());
        when(datasets.updateCase(eq("case_safe"), any())).thenReturn(correctedCase());
    }

    @AfterEach
    void clear() { UserContextHolder.clear(); }

    @Test
    void duplicateSourceReturnsExistingIssueWithoutAnotherRecord() {
        SearchInsight source = new SearchInsight(); source.setQueryHash("hash_safe");
        when(insights.selectOne(any())).thenReturn(source);
        assertSame(issue, service.create("kb_safe", QualityIssueSource.ZERO_HIT, "hash_safe"));
        verify(access).requireContent(issue); verifyNoInteractions(records, feedback);
        verify(issues, never()).insert(any(KnowledgeQualityIssue.class));
    }

    @Test
    void badFeedbackCreatesMaskedSummaryAndNeverConvertsTheBadChunk() {
        when(issues.selectOne(any())).thenReturn(null);
        RetrievalFeedback original = new RetrievalFeedback(); original.setVerdict(FeedbackVerdict.BAD);
        original.setDocId("doc_source"); original.setQuery("联系 qa@example.com 后仍未解决");
        when(feedback.selectOne(any())).thenReturn(original);
        var created = service.create("kb_safe", QualityIssueSource.BAD_FEEDBACK, "feedback_safe");
        assertFalse(created.getSummary().contains("qa@example.com"));
        assertEquals("doc_source", created.getSourceDocId());
        verify(access).requireDocument("kb_safe", "doc_source");
        verifyNoInteractions(datasets);
        assertNull(created.getCaseId());
    }

    @Test
    void nonexistentZeroHitAndGoodFeedbackCannotBecomeIssues() {
        when(issues.selectOne(any())).thenReturn(null);
        assertThrows(BizException.class, () -> service.create("kb_safe", QualityIssueSource.ZERO_HIT, "missing_hash"));
        RetrievalFeedback good = new RetrievalFeedback(); good.setVerdict(FeedbackVerdict.GOOD);
        when(feedback.selectOne(any())).thenReturn(good);
        assertThrows(BizException.class, () -> service.create("kb_safe", QualityIssueSource.BAD_FEEDBACK, "feedback_good"));
        verifyNoInteractions(records);
    }

    @Test
    void zeroHitUsesThePersistedMaskedDigestAndKeepsTheHashAsSourceIdentity() {
        when(issues.selectOne(any())).thenReturn(null);
        SearchInsight source = new SearchInsight(); source.setQueryDigest("脱敏后的摘要");
        source.setQueryHash("hash_safe");
        when(insights.selectOne(any())).thenReturn(source);
        var created = service.create("kb_safe", QualityIssueSource.ZERO_HIT, "insight_safe");
        assertEquals("脱敏后的摘要", created.getSummary()); assertEquals("hash_safe", created.getSourceId());
    }

    @Test
    void optimisticIssueWriteFailureNeverAppendsASuccessRecord() {
        when(issues.updateById(any(KnowledgeQualityIssue.class))).thenReturn(0);
        var error = assertThrows(BizException.class, () -> service.addNote("kb_safe", "issue_safe", 0, "处理中"));
        assertEquals(ErrorCode.QUALITY_ISSUE_CONFLICT, error.getErrorCode());
        verifyNoInteractions(records);
    }

    @Test
    void correctionNeedsBothEvaluationPermissionsAndAppRead() {
        principal(Set.of(PermissionCodes.FEEDBACK_MANAGE, PermissionCodes.EVAL_READ, PermissionCodes.APP_READ));
        assertThrows(BizException.class, () -> service.correct("kb_safe", "issue_safe", command(null)));
        verifyNoInteractions(datasets, records);
    }

    @Test
    void correctionCannotWriteAcrossKnowledgeBases() {
        EvalDataset other = new EvalDataset(); other.setKbId("kb_other");
        when(datasets.require("ds_safe")).thenReturn(other);
        assertThrows(BizException.class, () -> service.correct("kb_safe", "issue_safe", command(null)));
        verify(datasets, never()).createCase(any(), any()); verifyNoInteractions(records);
    }

    @Test
    void aStaleCaseRevisionCannotOverwriteAnEditFromTheEvaluationPage() {
        issue.setCaseId("case_safe"); issue.setDatasetId("ds_safe");
        EvalCase latest = correctedCase(); latest.setLockVersion(3);
        when(access.lockCase(issue)).thenReturn(latest);
        var error = assertThrows(BizException.class, () -> service.correct("kb_safe", "issue_safe", command(2)));
        assertEquals(ErrorCode.EVAL_DATASET_CONFLICT, error.getErrorCode());
        verify(datasets, never()).updateCase(any(), any()); verifyNoInteractions(records);
    }

    @Test
    void correctionWritesOneCaseAndMovesToWaitingWithTheConfirmedInput() {
        var saved = service.correct("kb_safe", "issue_safe", command(null));
        assertEquals(QualityIssueStatus.WAITING_REGRESSION, saved.getStatus());
        assertEquals("case_safe", saved.getCaseId()); assertTrue(saved.getExpectedCaseInput().contains("标准答案"));
        ArgumentCaptor<QualityIssueRecord> entry = ArgumentCaptor.forClass(QualityIssueRecord.class);
        verify(records).insert(entry.capture()); assertEquals("人工确认纠正", entry.getValue().getNote());
    }

    @Test
    void failedRegressionCannotChangeTheIssueToResolved() {
        issue.setStatus(QualityIssueStatus.WAITING_REGRESSION);
        when(regression.verify(issue, "run_failed")).thenThrow(BizException.invalidParam("quality failed"));
        assertThrows(BizException.class, () -> service.resolve("kb_safe", "issue_safe", 0, "run_failed", "确认"));
        assertEquals(QualityIssueStatus.WAITING_REGRESSION, issue.getStatus());
        verify(issues, never()).updateById(any(KnowledgeQualityIssue.class)); verifyNoInteractions(records);
    }

    private KnowledgeQualityIssueService.Correction command(Integer caseRevision) {
        var input = EvalCaseCommand.builder().query("资料以外的问题").expectedRefusal(true).anchorType(AnchorType.DOCUMENT)
                .evidences(List.of()).note("人工确认纠正").build();
        return new KnowledgeQualityIssueService.Correction(0, caseRevision, QualityIssueReason.MISSING_KNOWLEDGE,
                "ds_safe", "av_safe", input);
    }

    private EvalCase correctedCase() {
        EvalCase evalCase = new EvalCase(); evalCase.setCaseId("case_safe"); evalCase.setDatasetId("ds_safe");
        evalCase.setQuery("资料以外的问题"); evalCase.setExpectedAnswer("标准答案"); evalCase.setExpectedRefusal(true);
        evalCase.setEvidences("[]"); evalCase.setAnchorType(AnchorType.DOCUMENT); evalCase.setStatus(CaseStatus.ACTIVE);
        return evalCase;
    }

    private void principal(Set<String> permissions) {
        UserContextHolder.set(new UserPrincipal("user_safe", "tenant_safe", "user_safe", "处理人", UserSource.LOCAL,
                Set.of(), Set.of(), permissions, true, Set.of(), true, Set.of()));
    }
}
