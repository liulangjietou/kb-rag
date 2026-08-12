package io.kbrag.app.auth;

import io.kbrag.app.support.MybatisLambdaCache;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.constant.PermissionCodes;
import io.kbrag.domain.context.UserContextHolder;
import io.kbrag.domain.entity.Annotation;
import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.entity.DocAcl;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.entity.EvalCase;
import io.kbrag.domain.entity.EvalDataset;
import io.kbrag.domain.entity.EvalRun;
import io.kbrag.domain.entity.ExtSource;
import io.kbrag.domain.entity.KnowledgeBase;
import io.kbrag.domain.entity.RetrievalFeedback;
import io.kbrag.domain.enums.DocVisibility;
import io.kbrag.domain.enums.UserSource;
import io.kbrag.domain.mapper.AnnotationMapper;
import io.kbrag.domain.mapper.ChunkMapper;
import io.kbrag.domain.mapper.DocAclMapper;
import io.kbrag.domain.mapper.DocumentMapper;
import io.kbrag.domain.mapper.EvalCaseMapper;
import io.kbrag.domain.mapper.EvalDatasetMapper;
import io.kbrag.domain.mapper.EvalRunMapper;
import io.kbrag.domain.mapper.ExtSourceMapper;
import io.kbrag.domain.mapper.KnowledgeBaseMapper;
import io.kbrag.domain.mapper.RetrievalFeedbackMapper;
import io.kbrag.domain.model.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the two decisions this guard makes and the order they happen in.
 *
 * <p>The tenant half is expressed through the knowledge base mapper: on a console thread the MyBatis
 * fence trims that statement to the caller's tenant, so "the base of another tenant" is modelled here
 * as the mapper answering {@code null}. Every subordinate resource has one such case, because before
 * this class resolved the root they were all reachable across tenants by id alone.
 *
 * <p>The data scope half is the M16 contract section 5 content gate, unchanged in substance: the four
 * quadrants of "base in scope" times "grant held" of a restricted document, the {@code doc:review}
 * bypass, and the refusal to let {@code kb_scope_all} stand in for a grant - the scope answers "which
 * bases", never "which rows inside one".
 *
 * @author owlzhangfq@gmail.com
 */
class KbResourceGuardTest {

    private static final String KB_ID = "kb_alpha";
    private static final String DOC_ID = "doc_1";
    private static final String CHUNK_ID = "chk_1";
    private static final String ANNOTATION_ID = "ann_1";
    private static final String DATASET_ID = "ds_1";
    private static final String CASE_ID = "case_1";
    private static final String RUN_ID = "run_1";
    private static final String SOURCE_ID = "ext_1";
    private static final String FEEDBACK_ID = "fb_1";
    private static final String GRANTED_ROLE = "role_g1";

    private KnowledgeBaseMapper knowledgeBaseMapper;
    private DocumentMapper documentMapper;
    private ChunkMapper chunkMapper;
    private AnnotationMapper annotationMapper;
    private EvalDatasetMapper evalDatasetMapper;
    private EvalCaseMapper evalCaseMapper;
    private EvalRunMapper evalRunMapper;
    private ExtSourceMapper extSourceMapper;
    private RetrievalFeedbackMapper retrievalFeedbackMapper;
    private DocAclMapper docAclMapper;
    private KbResourceGuard guard;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.register(KnowledgeBase.class, Document.class, DocAcl.class, Chunk.class,
                Annotation.class, EvalDataset.class, EvalCase.class, EvalRun.class, ExtSource.class,
                RetrievalFeedback.class);
        knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        documentMapper = mock(DocumentMapper.class);
        chunkMapper = mock(ChunkMapper.class);
        annotationMapper = mock(AnnotationMapper.class);
        evalDatasetMapper = mock(EvalDatasetMapper.class);
        evalCaseMapper = mock(EvalCaseMapper.class);
        evalRunMapper = mock(EvalRunMapper.class);
        extSourceMapper = mock(ExtSourceMapper.class);
        retrievalFeedbackMapper = mock(RetrievalFeedbackMapper.class);
        docAclMapper = mock(DocAclMapper.class);
        guard = new KbResourceGuard(knowledgeBaseMapper, documentMapper, chunkMapper, annotationMapper,
                evalDatasetMapper, evalCaseMapper, evalRunMapper, extSourceMapper,
                retrievalFeedbackMapper, docAclMapper);
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    // ---------------------------------------------------------------- tenant

    @Test
    void shouldReportADocumentOfAnotherTenantAsNotFound() {
        givenDocument(DocVisibility.INHERIT);
        givenBaseOfAnotherTenant();
        bindCaller(Set.of(KB_ID), Set.of(), Set.of(), false);

        assertNotFound(() -> guard.requireDocumentAccess(DOC_ID));
    }

    @Test
    void shouldReportAChunkOfAnotherTenantAsNotFound() {
        Chunk chunk = new Chunk();
        chunk.setChunkId(CHUNK_ID);
        chunk.setKbId(KB_ID);
        when(chunkMapper.selectOne(any())).thenReturn(chunk);
        givenBaseOfAnotherTenant();
        bindCaller(Set.of(KB_ID), Set.of(), Set.of(), false);

        assertNotFound(() -> guard.requireChunkAccess(CHUNK_ID));
    }

    @Test
    void shouldReportAnAnnotationOfAnotherTenantAsNotFound() {
        Annotation annotation = new Annotation();
        annotation.setAnnotationId(ANNOTATION_ID);
        annotation.setKbId(KB_ID);
        when(annotationMapper.selectOne(any())).thenReturn(annotation);
        givenBaseOfAnotherTenant();
        bindCaller(Set.of(KB_ID), Set.of(), Set.of(), false);

        assertNotFound(() -> guard.requireAnnotationAccess(ANNOTATION_ID));
    }

    @Test
    void shouldReportAnExternalSourceOfAnotherTenantAsNotFound() {
        ExtSource source = new ExtSource();
        source.setSourceId(SOURCE_ID);
        source.setKbId(KB_ID);
        when(extSourceMapper.selectOne(any())).thenReturn(source);
        givenBaseOfAnotherTenant();
        bindCaller(Set.of(KB_ID), Set.of(), Set.of(), false);

        // The endpoints behind this one overwrite an endpoint and an access key, probe a foreign store
        // with someone else's credentials and hard delete the registration.
        assertNotFound(() -> guard.requireExtSourceAccess(SOURCE_ID));
    }

    @Test
    void shouldReportAFeedbackOfAnotherTenantAsNotFound() {
        RetrievalFeedback feedback = new RetrievalFeedback();
        feedback.setFeedbackId(FEEDBACK_ID);
        feedback.setKbId(KB_ID);
        when(retrievalFeedbackMapper.selectOne(any())).thenReturn(feedback);
        givenBaseOfAnotherTenant();
        bindCaller(Set.of(KB_ID), Set.of(), Set.of(), false);

        assertNotFound(() -> guard.requireFeedbackAccess(FEEDBACK_ID));
    }

    @Test
    void shouldReportADataSetOfAnotherTenantAsNotFound() {
        // t_kb_eval_dataset is a fenced root, so the foreign row is filtered out of this statement
        // itself - no second hop is involved.
        when(evalDatasetMapper.selectOne(any())).thenReturn(null);
        bindCaller(Set.of(KB_ID), Set.of(), Set.of(), false);

        assertNotFound(() -> guard.requireDatasetAccess(DATASET_ID));
    }

    @Test
    void shouldReportACaseOfAnotherTenantAsNotFound() {
        EvalCase evalCase = new EvalCase();
        evalCase.setCaseId(CASE_ID);
        evalCase.setDatasetId(DATASET_ID);
        when(evalCaseMapper.selectOne(any())).thenReturn(evalCase);
        when(evalDatasetMapper.selectOne(any())).thenReturn(null);
        bindCaller(Set.of(KB_ID), Set.of(), Set.of(), false);

        assertNotFound(() -> guard.requireCaseAccess(CASE_ID));
    }

    @Test
    void shouldReportARunOfAnotherTenantAsNotFound() {
        givenRun();
        when(evalDatasetMapper.selectOne(any())).thenReturn(null);
        bindCaller(Set.of(KB_ID), Set.of(), Set.of(), false);

        assertNotFound(() -> guard.requireRunAccess(RUN_ID));
    }

    @Test
    void shouldReportAKnowledgeBaseOfAnotherTenantAsNotFound() {
        givenBaseOfAnotherTenant();
        bindCaller(Set.of(KB_ID), Set.of(), Set.of(), false);

        assertNotFound(() -> guard.requireKb(KB_ID));
    }

    @Test
    void shouldResolveARunThroughItsDataSetRatherThanItsOwnKbColumn() {
        givenRun();
        givenDataSet();
        bindCaller(Set.of(KB_ID), Set.of(), Set.of(), false);

        assertDoesNotThrow(() -> guard.requireRunAccess(RUN_ID));
        // The run row also carries a kb_id, and reading the base straight from it would skip the one
        // fenced table in this chain.
        verify(knowledgeBaseMapper, never()).selectOne(any());
    }

    // ------------------------------------------------- tenant before scope

    @Test
    void shouldNotBeShortCircuitedByTheFullKnowledgeBaseScope() {
        givenDocument(DocVisibility.INHERIT);
        givenBaseOfAnotherTenant();
        // kb_scope_all is what every built in role ships with, so the removed short circuit used to
        // fire for essentially every real caller - and took the tenant lookup down with it.
        bindCaller(Set.of(), Set.of(), Set.of(), true);

        assertNotFound(() -> guard.requireDocumentAccess(DOC_ID));
    }

    @Test
    void shouldAnswerNotFoundRatherThanForbiddenWhenBothDecisionsWouldFail() {
        givenDocument(DocVisibility.INHERIT);
        givenBaseOfAnotherTenant();
        // Outside the tenant *and* outside the data scope. Answering 403 here would confirm the id
        // exists somewhere, which is exactly what the 404 stance is for.
        bindCaller(Set.of("kb_other"), Set.of(), Set.of(), false);

        assertNotFound(() -> guard.requireDocumentAccess(DOC_ID));
    }

    @Test
    void shouldAnswerForbiddenInsideTheOwnTenantButOutsideTheScope() {
        givenDocument(DocVisibility.INHERIT);
        givenBase();
        bindCaller(Set.of("kb_other"), Set.of(), Set.of(), false);

        assertEquals(ErrorCode.FORBIDDEN,
                assertThrows(BizException.class, () -> guard.requireDocumentAccess(DOC_ID)).getErrorCode());
    }

    @Test
    void shouldPassAThreadWithoutAConsolePrincipal() {
        givenDocument(DocVisibility.INHERIT);
        givenBase();

        // The open API and the scheduled passes carry no principal: the fence is skipped on those
        // threads and no data scope exists to trim against. Unchanged pre-existing semantics.
        assertDoesNotThrow(() -> guard.requireDocumentAccess(DOC_ID));
    }

    @Test
    void shouldReportAMissingSubordinateRowWithoutReadingTheBase() {
        when(documentMapper.selectOne(any())).thenReturn(null);
        bindCaller(Set.of(KB_ID), Set.of(), Set.of(), false);

        assertNotFound(() -> guard.requireDocumentAccess(DOC_ID));
        verify(knowledgeBaseMapper, never()).selectOne(any());
    }

    // ----------------------------------------------------------- data scope

    @Test
    void shouldPassAnUnrestrictedDocumentInsideTheScope() {
        givenDocument(DocVisibility.INHERIT);
        givenBase();
        bindCaller(Set.of(KB_ID), Set.of(), Set.of(), false);

        assertDoesNotThrow(() -> guard.requireDocumentContentAccess(DOC_ID));
    }

    @Test
    void shouldRefuseADocumentOutsideTheScopeEvenWhenAGrantIsHeld() {
        givenDocument(DocVisibility.RESTRICTED);
        givenBase();
        // The base fence comes first: a document grant is meaningless in a base the caller cannot
        // see at all.
        bindCaller(Set.of("kb_other"), Set.of(GRANTED_ROLE), Set.of(), false);

        assertThrows(BizException.class, () -> guard.requireDocumentContentAccess(DOC_ID));
    }

    @Test
    void shouldPassARestrictedDocumentWhenAGrantedRoleIsHeld() {
        givenDocument(DocVisibility.RESTRICTED);
        givenBase();
        when(docAclMapper.selectList(any())).thenReturn(List.of(grant(GRANTED_ROLE)));
        bindCaller(Set.of(KB_ID), Set.of(GRANTED_ROLE), Set.of(), false);

        assertDoesNotThrow(() -> guard.requireDocumentContentAccess(DOC_ID));
    }

    @Test
    void shouldRefuseARestrictedDocumentWithoutAGrant() {
        givenDocument(DocVisibility.RESTRICTED);
        givenBase();
        when(docAclMapper.selectList(any())).thenReturn(List.of(grant(GRANTED_ROLE)));
        bindCaller(Set.of(KB_ID), Set.of("role_other"), Set.of(), false);

        assertThrows(BizException.class, () -> guard.requireDocumentContentAccess(DOC_ID));
    }

    @Test
    void shouldLetTheReviewerThroughWithoutAGrant() {
        givenDocument(DocVisibility.RESTRICTED);
        givenBase();
        // Whoever can change a clearance cannot be hidden from the content it protects.
        bindCaller(Set.of(KB_ID), Set.of("role_other"), Set.of(PermissionCodes.DOC_REVIEW), false);

        assertDoesNotThrow(() -> guard.requireDocumentContentAccess(DOC_ID));
    }

    @Test
    void shouldNotLetTheFullKnowledgeBaseScopeStandInForAGrant() {
        givenDocument(DocVisibility.RESTRICTED);
        givenBase();
        when(docAclMapper.selectList(any())).thenReturn(List.of(grant(GRANTED_ROLE)));
        // kb_scope_all answers "which bases", never "which rows inside one".
        bindCaller(Set.of(), Set.of("role_other"), Set.of(), true);

        assertThrows(BizException.class, () -> guard.requireDocumentContentAccess(DOC_ID));
    }

    @Test
    void shouldRefuseTheRestrictedContentOfACallerWithoutAConsolePrincipal() {
        givenDocument(DocVisibility.RESTRICTED);
        givenBase();

        // The API key path holds no roles and is therefore refused every restricted document.
        assertThrows(BizException.class, () -> guard.requireDocumentContentAccess(DOC_ID));
    }

    @Test
    void shouldReportAMissingDocumentAsNotFound() {
        when(documentMapper.selectOne(any())).thenReturn(null);
        bindCaller(Set.of(KB_ID), Set.of(), Set.of(), false);

        assertNotFound(() -> guard.requireDocumentContentAccess(DOC_ID));
    }

    // ---------------------------------------------------------------- setup

    private void givenDocument(DocVisibility visibility) {
        Document document = new Document();
        document.setDocId(DOC_ID);
        document.setKbId(KB_ID);
        document.setVisibility(visibility);
        when(documentMapper.selectOne(any())).thenReturn(document);
    }

    private void givenRun() {
        EvalRun run = new EvalRun();
        run.setRunId(RUN_ID);
        run.setDatasetId(DATASET_ID);
        run.setKbId(KB_ID);
        when(evalRunMapper.selectOne(any())).thenReturn(run);
    }

    private void givenDataSet() {
        EvalDataset dataset = new EvalDataset();
        dataset.setDatasetId(DATASET_ID);
        dataset.setKbId(KB_ID);
        when(evalDatasetMapper.selectOne(any())).thenReturn(dataset);
    }

    /** The fence let the base through: it belongs to the caller's tenant. */
    private void givenBase() {
        KnowledgeBase base = new KnowledgeBase();
        base.setKbId(KB_ID);
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(base);
    }

    /** The fence trimmed the base away: it belongs to another tenant, or it is gone. */
    private void givenBaseOfAnotherTenant() {
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(null);
    }

    private void bindCaller(Set<String> kbIds, Set<String> roleIds, Set<String> permissions,
                            boolean kbScopeAll) {
        UserContextHolder.set(new UserPrincipal("usr_1", "tnt_default0000000", "alice", "Alice",
                UserSource.LOCAL, Set.of(), roleIds, permissions, kbScopeAll, kbIds));
    }

    private DocAcl grant(String roleId) {
        DocAcl grant = new DocAcl();
        grant.setDocumentId(DOC_ID);
        grant.setRoleId(roleId);
        return grant;
    }

    private void assertNotFound(org.junit.jupiter.api.function.Executable call) {
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(BizException.class, call).getErrorCode());
    }
}
