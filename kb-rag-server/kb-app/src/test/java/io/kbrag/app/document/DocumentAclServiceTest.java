package io.kbrag.app.document;

import io.kbrag.app.support.MybatisLambdaCache;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.context.UserContextHolder;
import io.kbrag.domain.entity.DocAcl;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.entity.DocumentVersion;
import io.kbrag.domain.entity.Role;
import io.kbrag.domain.enums.DocVisibility;
import io.kbrag.domain.enums.UserSource;
import io.kbrag.domain.mapper.DocAclMapper;
import io.kbrag.domain.mapper.DocumentMapper;
import io.kbrag.domain.mapper.DocumentVersionMapper;
import io.kbrag.domain.mapper.RoleMapper;
import io.kbrag.domain.model.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers the document level visibility of the M16 contract section 5: the retrieval trim that drops
 * the versions of restricted documents the caller may not read - including the roleless open API
 * caller - the delete-then-insert grant rebinding, and the residue cleanup hooks of the document and
 * role delete chains.
 *
 * <p>The trim is exercised through {@link UserContextHolder} directly because the judgement is
 * defined against the caller of the current request; a frozen snapshot set takes the very same code
 * path, so passing one here is the "snapshot set" branch of the contract.
 *
 * @author owlzhangfq@gmail.com
 */
class DocumentAclServiceTest {

    private static final String KB_ID = "kb_alpha";
    private static final String DOC_ID = "doc_1";
    private static final String GRANTED_ROLE = "role_g1";

    private DocumentMapper documentMapper;
    private DocumentVersionMapper documentVersionMapper;
    private DocAclMapper docAclMapper;
    private RoleMapper roleMapper;
    private DocumentAclService service;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.register(Document.class, DocumentVersion.class, DocAcl.class, Role.class);
        documentMapper = mock(DocumentMapper.class);
        documentVersionMapper = mock(DocumentVersionMapper.class);
        docAclMapper = mock(DocAclMapper.class);
        roleMapper = mock(RoleMapper.class);
        service = new DocumentAclService(documentMapper, documentVersionMapper, docAclMapper, roleMapper);
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void shouldReturnTheSetUntouchedWhenNoDocumentIsRestricted() {
        when(documentMapper.selectList(any())).thenReturn(List.of());

        List<String> trimmed = service.trimRestricted(KB_ID, List.of("dv_1", "dv_2"));

        // The common path costs one indexed query and nothing else.
        assertEquals(List.of("dv_1", "dv_2"), trimmed);
        verifyNoInteractions(docAclMapper);
    }

    @Test
    void shouldKeepTheVersionsOfARestrictedDocumentTheCallerMayRead() {
        UserContextHolder.set(principal(Set.of(GRANTED_ROLE)));
        when(documentMapper.selectList(any())).thenReturn(List.of(restrictedDoc(DOC_ID)));
        when(docAclMapper.selectList(any())).thenReturn(List.of(grant(DOC_ID, GRANTED_ROLE)));

        List<String> trimmed = service.trimRestricted(KB_ID, List.of("dv_1", "dv_2"));

        assertEquals(List.of("dv_1", "dv_2"), trimmed);
        verify(documentVersionMapper, never()).selectList(any());
    }

    @Test
    void shouldDropTheVersionsOfARestrictedDocumentTheCallerMayNotRead() {
        UserContextHolder.set(principal(Set.of("role_other")));
        when(documentMapper.selectList(any())).thenReturn(List.of(restrictedDoc(DOC_ID)));
        when(docAclMapper.selectList(any())).thenReturn(List.of(grant(DOC_ID, GRANTED_ROLE)));
        when(documentVersionMapper.selectList(any())).thenReturn(List.of(version("dv_1", DOC_ID)));

        List<String> trimmed = service.trimRestricted(KB_ID, List.of("dv_1", "dv_2"));

        assertEquals(List.of("dv_2"), trimmed);
    }

    @Test
    void shouldDropEveryRestrictedDocumentForTheOpenApiCaller() {
        // No console principal: an end user has no roles, and a released application must not
        // become a bypass around a clearance - this also covers a frozen snapshot set, which is
        // trimmed by the same call after the release froze which versions answer.
        when(documentMapper.selectList(any())).thenReturn(List.of(restrictedDoc(DOC_ID)));
        when(docAclMapper.selectList(any())).thenReturn(List.of(grant(DOC_ID, GRANTED_ROLE)));
        when(documentVersionMapper.selectList(any())).thenReturn(List.of(version("dv_1", DOC_ID)));

        List<String> trimmed = service.trimRestricted(KB_ID, List.of("dv_1", "dv_2"));

        assertEquals(List.of("dv_2"), trimmed);
    }

    @Test
    void shouldReturnAnEmptySetWithoutQueryingAnything() {
        assertEquals(List.of(), service.trimRestricted(KB_ID, List.of()));

        verifyNoInteractions(documentMapper);
    }

    @Test
    void shouldRebindTheGrantsAsDeleteThenInsert() {
        when(documentMapper.selectOne(any())).thenReturn(inheritDoc());
        when(roleMapper.selectCount(any())).thenReturn(2L);

        service.updateVisibility(KB_ID, DOC_ID, DocVisibility.RESTRICTED,
                List.of("role_a", "role_b", "role_a"));

        // Delete then insert, the discipline of the role association tables: the logical delete
        // column would resurrect history under an update in place. Duplicates collapse first.
        verify(docAclMapper).deleteByDocumentId(DOC_ID);
        ArgumentCaptor<DocAcl> inserted = ArgumentCaptor.forClass(DocAcl.class);
        verify(docAclMapper, times(2)).insert(inserted.capture());
        assertEquals(List.of("role_a", "role_b"),
                inserted.getAllValues().stream().map(DocAcl::getRoleId).toList());
        ArgumentCaptor<Document> updated = ArgumentCaptor.forClass(Document.class);
        verify(documentMapper).updateById(updated.capture());
        assertEquals(DocVisibility.RESTRICTED, updated.getValue().getVisibility());
    }

    @Test
    void shouldRefuseRolesTheTenantDoesNotKnow() {
        when(documentMapper.selectOne(any())).thenReturn(inheritDoc());
        // The count query runs under the tenant fence, so a role of another tenant is simply not
        // counted and the mismatch is the refusal.
        when(roleMapper.selectCount(any())).thenReturn(1L);

        assertThrows(BizException.class, () -> service.updateVisibility(KB_ID, DOC_ID,
                DocVisibility.RESTRICTED, List.of("role_a", "role_foreign")));

        verify(docAclMapper, never()).insert(any(DocAcl.class));
    }

    @Test
    void shouldClearTheGrantsWhenADocumentGoesBackToInherit() {
        Document document = inheritDoc();
        document.setVisibility(DocVisibility.RESTRICTED);
        when(documentMapper.selectOne(any())).thenReturn(document);

        service.updateVisibility(KB_ID, DOC_ID, DocVisibility.INHERIT, List.of());

        verify(docAclMapper).deleteByDocumentId(DOC_ID);
        verify(docAclMapper, never()).insert(any(DocAcl.class));
        verifyNoInteractions(roleMapper);
    }

    @Test
    void shouldRefuseADocumentOfAnotherKnowledgeBase() {
        when(documentMapper.selectOne(any())).thenReturn(inheritDoc());

        assertThrows(BizException.class,
                () -> service.visibility("kb_other", DOC_ID));
    }

    @Test
    void shouldListTheGrantedRolesOfARestrictedDocument() {
        Document document = inheritDoc();
        document.setVisibility(DocVisibility.RESTRICTED);
        when(documentMapper.selectOne(any())).thenReturn(document);
        when(docAclMapper.selectList(any()))
                .thenReturn(List.of(grant(DOC_ID, "role_a"), grant(DOC_ID, "role_a"),
                        grant(DOC_ID, "role_b")));

        DocumentAclService.VisibilityView view = service.visibility(KB_ID, DOC_ID);

        assertEquals(DocVisibility.RESTRICTED, view.visibility());
        assertEquals(List.of("role_a", "role_b"), view.roleIds());
    }

    @Test
    void shouldRemoveTheResidueOfDeletedDocumentsAndRoles() {
        when(docAclMapper.deleteByDocumentId(DOC_ID)).thenReturn(2);
        when(docAclMapper.deleteByRoleId(GRANTED_ROLE)).thenReturn(3);

        service.detachDocument(DOC_ID);
        service.detachRole(GRANTED_ROLE);

        // A grant row without its document or role would silently keep restricting or granting
        // whatever recycles the id; the delete chains must leave no residue behind.
        verify(docAclMapper).deleteByDocumentId(DOC_ID);
        verify(docAclMapper).deleteByRoleId(GRANTED_ROLE);
    }

    private UserPrincipal principal(Set<String> roleIds) {
        return new UserPrincipal("usr_1", "tnt_default0000000", "alice", "Alice", UserSource.LOCAL,
                Set.of(), roleIds, Set.of(), false, Set.of());
    }

    private Document restrictedDoc(String docId) {
        Document document = new Document();
        document.setDocId(docId);
        document.setKbId(KB_ID);
        document.setVisibility(DocVisibility.RESTRICTED);
        return document;
    }

    private Document inheritDoc() {
        Document document = new Document();
        document.setDocId(DOC_ID);
        document.setKbId(KB_ID);
        // Pre-M16 rows carry a null column and are INHERIT by contract.
        document.setVisibility(null);
        return document;
    }

    private DocAcl grant(String docId, String roleId) {
        DocAcl grant = new DocAcl();
        grant.setDocumentId(docId);
        grant.setRoleId(roleId);
        return grant;
    }

    private DocumentVersion version(String versionId, String docId) {
        DocumentVersion version = new DocumentVersion();
        version.setVersionId(versionId);
        version.setDocId(docId);
        return version;
    }
}
