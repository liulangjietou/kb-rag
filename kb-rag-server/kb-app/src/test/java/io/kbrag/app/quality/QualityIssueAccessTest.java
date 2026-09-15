package io.kbrag.app.quality;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.app.appcenter.AppVersionService;
import io.kbrag.app.auth.KbResourceGuard;
import io.kbrag.app.support.MybatisLambdaCache;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.constant.PermissionCodes;
import io.kbrag.domain.context.UserContextHolder;
import io.kbrag.domain.entity.App;
import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.entity.EvalCase;
import io.kbrag.domain.entity.EvalResult;
import io.kbrag.domain.entity.KnowledgeBase;
import io.kbrag.domain.entity.KnowledgeQualityIssue;
import io.kbrag.domain.enums.UserSource;
import io.kbrag.domain.mapper.AppMapper;
import io.kbrag.domain.mapper.ChunkMapper;
import io.kbrag.domain.mapper.DocumentMapper;
import io.kbrag.domain.mapper.EvalCaseMapper;
import io.kbrag.domain.mapper.KnowledgeBaseMapper;
import io.kbrag.domain.model.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** SQL 所属关系与撤权检查覆盖历史说明和实际召回资料。 */
class QualityIssueAccessTest {
    private final KnowledgeBaseMapper bases = mock(KnowledgeBaseMapper.class);
    private final DocumentMapper documents = mock(DocumentMapper.class);
    private final ChunkMapper chunks = mock(ChunkMapper.class);
    private final EvalCaseMapper cases = mock(EvalCaseMapper.class);
    private final AppMapper apps = mock(AppMapper.class);
    private final KbResourceGuard guard = mock(KbResourceGuard.class);
    private final QualityIssueAccess access = new QualityIssueAccess(bases, documents, chunks, cases, apps, guard, mock(AppVersionService.class));

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.register(KnowledgeBase.class, Document.class, Chunk.class, EvalCase.class, App.class);
        UserContextHolder.set(new UserPrincipal("u_safe", "tenant_safe", "user_safe", "处理人", UserSource.LOCAL,
                Set.of(), Set.of(), Set.of(PermissionCodes.TENANT_MANAGE), true, Set.of(), true, Set.of()));
    }

    @AfterEach
    void clear() { UserContextHolder.clear(); }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void evenPlatformOperatorsNeedAnExplicitTenantPredicateOnTheKnowledgeRoot() {
        assertThrows(BizException.class, () -> access.requireKb("kb_other"));
        ArgumentCaptor<LambdaQueryWrapper<KnowledgeBase>> query = ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
        verify(bases).selectOne(query.capture());
        assertTrue(query.getValue().getSqlSegment().contains("tenant_id"));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue("tenant_safe"));
        verifyNoInteractions(documents, chunks, cases, apps);
    }

    @Test
    void losingAHistoricalDocumentPermissionHidesTheIssueContent() {
        KnowledgeQualityIssue issue = new KnowledgeQualityIssue(); issue.setKbId("kb_safe");
        issue.setProtectedDocIds("[\"doc_old\"]");
        when(documents.selectOne(any())).thenReturn(new Document());
        doThrow(BizException.forbidden("restricted")).when(guard).requireDocumentContentAccess("doc_old");
        assertFalse(access.canReadContent(issue));
        assertThrows(BizException.class, () -> access.requireContent(issue));
    }

    @Test
    void correctedCaseContentRequiresEvaluationPermissionBeforeLoadingItsVersion() {
        KnowledgeQualityIssue issue = new KnowledgeQualityIssue(); issue.setExpectedCaseInput("{}");
        assertFalse(access.canReadContent(issue));
        verifyNoInteractions(apps, documents);
    }

    @Test
    void allRecalledDocumentsMustRemainReadableAndMissingChunksCannotBeIgnored() {
        EvalResult result = new EvalResult(); result.setRecalledChunkIds("[\"chunk_extra\"]");
        when(chunks.selectList(any())).thenReturn(List.of());
        assertThrows(BizException.class, () -> access.requireResult("kb_safe", result));
        Chunk chunk = new Chunk(); chunk.setDocId("doc_extra");
        when(chunks.selectList(any())).thenReturn(List.of(chunk));
        when(documents.selectOne(any())).thenReturn(new Document());
        doThrow(BizException.forbidden("restricted")).when(guard).requireDocumentContentAccess("doc_extra");
        assertThrows(BizException.class, () -> access.requireResult("kb_safe", result));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void caseLockIsScopedToTheLinkedDataset() {
        KnowledgeQualityIssue issue = new KnowledgeQualityIssue(); issue.setKbId("kb_safe");
        issue.setCaseId("case_safe"); issue.setDatasetId("ds_safe");
        EvalCase current = new EvalCase(); current.setEvidences("[]");
        when(cases.selectOne(any())).thenReturn(current);
        assertSame(current, access.lockCase(issue));
        ArgumentCaptor<LambdaQueryWrapper<EvalCase>> query = ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
        verify(cases).selectOne(query.capture());
        String sql = query.getValue().getSqlSegment();
        assertTrue(sql.contains("dataset_id")); assertTrue(sql.endsWith("FOR UPDATE"));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue("ds_safe"));
    }
}
