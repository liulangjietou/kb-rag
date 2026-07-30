package io.kbrag.app.auth;

import io.kbrag.app.support.MybatisLambdaCache;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.constant.PermissionCodes;
import io.kbrag.domain.context.UserContextHolder;
import io.kbrag.domain.entity.DocAcl;
import io.kbrag.domain.entity.Document;
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
import io.kbrag.domain.mapper.RetrievalFeedbackMapper;
import io.kbrag.domain.mapper.WebSourceMapper;
import io.kbrag.domain.model.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the content gate of the M16 contract section 5: the four quadrants of "base in scope" times
 * "grant held" of a restricted document, the {@code doc:review} bypass, and the deliberate refusal
 * to let {@code kb_scope_all} stand in for a grant - the scope answers "which bases", never "which
 * rows inside one".
 *
 * @author owlzhangfq@gmail.com
 */
class KbScopeGuardTest {

    private static final String KB_ID = "kb_alpha";
    private static final String DOC_ID = "doc_1";
    private static final String GRANTED_ROLE = "role_g1";

    private DocumentMapper documentMapper;
    private DocAclMapper docAclMapper;
    private KbScopeGuard guard;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.register(Document.class, DocAcl.class);
        documentMapper = mock(DocumentMapper.class);
        docAclMapper = mock(DocAclMapper.class);
        guard = new KbScopeGuard(documentMapper, mock(ChunkMapper.class), mock(AnnotationMapper.class),
                mock(EvalDatasetMapper.class), mock(EvalCaseMapper.class), mock(EvalRunMapper.class),
                mock(ExtSourceMapper.class), mock(WebSourceMapper.class),
                mock(RetrievalFeedbackMapper.class), docAclMapper);
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void shouldPassAnUnrestrictedDocumentInsideTheScope() {
        givenDocument(DocVisibility.INHERIT);
        bindCaller(Set.of(KB_ID), Set.of(), Set.of(), false);

        assertDoesNotThrow(() -> guard.requireDocumentContentAccess(DOC_ID));
    }

    @Test
    void shouldRefuseADocumentOutsideTheScopeEvenWhenAGrantIsHeld() {
        givenDocument(DocVisibility.RESTRICTED);
        // The base fence comes first: a document grant is meaningless in a base the caller cannot
        // see at all.
        bindCaller(Set.of("kb_other"), Set.of(GRANTED_ROLE), Set.of(), false);

        assertThrows(BizException.class, () -> guard.requireDocumentContentAccess(DOC_ID));
    }

    @Test
    void shouldPassARestrictedDocumentWhenAGrantedRoleIsHeld() {
        givenDocument(DocVisibility.RESTRICTED);
        when(docAclMapper.selectList(any())).thenReturn(List.of(grant(GRANTED_ROLE)));
        bindCaller(Set.of(KB_ID), Set.of(GRANTED_ROLE), Set.of(), false);

        assertDoesNotThrow(() -> guard.requireDocumentContentAccess(DOC_ID));
    }

    @Test
    void shouldRefuseARestrictedDocumentWithoutAGrant() {
        givenDocument(DocVisibility.RESTRICTED);
        when(docAclMapper.selectList(any())).thenReturn(List.of(grant(GRANTED_ROLE)));
        bindCaller(Set.of(KB_ID), Set.of("role_other"), Set.of(), false);

        assertThrows(BizException.class, () -> guard.requireDocumentContentAccess(DOC_ID));
    }

    @Test
    void shouldLetTheReviewerThroughWithoutAGrant() {
        givenDocument(DocVisibility.RESTRICTED);
        // Whoever can change a clearance cannot be hidden from the content it protects.
        bindCaller(Set.of(KB_ID), Set.of("role_other"), Set.of(PermissionCodes.DOC_REVIEW), false);

        assertDoesNotThrow(() -> guard.requireDocumentContentAccess(DOC_ID));
    }

    @Test
    void shouldNotLetTheFullKnowledgeBaseScopeStandInForAGrant() {
        givenDocument(DocVisibility.RESTRICTED);
        when(docAclMapper.selectList(any())).thenReturn(List.of(grant(GRANTED_ROLE)));
        // kb_scope_all answers "which bases", never "which rows inside one".
        bindCaller(Set.of(), Set.of("role_other"), Set.of(), true);

        assertThrows(BizException.class, () -> guard.requireDocumentContentAccess(DOC_ID));
    }

    @Test
    void shouldRefuseTheCallerWithoutAConsolePrincipal() {
        givenDocument(DocVisibility.RESTRICTED);

        // The API key path holds no roles and is therefore refused every restricted document.
        assertThrows(BizException.class, () -> guard.requireDocumentContentAccess(DOC_ID));
    }

    @Test
    void shouldReportAMissingDocumentAsNotFound() {
        when(documentMapper.selectOne(any())).thenReturn(null);
        bindCaller(Set.of(KB_ID), Set.of(), Set.of(), false);

        assertThrows(BizException.class, () -> guard.requireDocumentContentAccess(DOC_ID));
    }

    private void givenDocument(DocVisibility visibility) {
        Document document = new Document();
        document.setDocId(DOC_ID);
        document.setKbId(KB_ID);
        document.setVisibility(visibility);
        when(documentMapper.selectOne(any())).thenReturn(document);
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
}
