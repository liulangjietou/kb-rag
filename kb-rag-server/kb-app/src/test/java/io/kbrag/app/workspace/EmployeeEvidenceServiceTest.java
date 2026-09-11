package io.kbrag.app.workspace;

import io.kbrag.app.document.DocumentAclService;
import io.kbrag.app.retrieval.RetrievalNodeView;
import io.kbrag.app.support.MybatisLambdaCache;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.context.UserContextHolder;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.entity.DocumentVersion;
import io.kbrag.domain.enums.PublishStatus;
import io.kbrag.domain.enums.UserSource;
import io.kbrag.domain.mapper.DocumentMapper;
import io.kbrag.domain.mapper.DocumentVersionMapper;
import io.kbrag.domain.model.EmployeeCitation;
import io.kbrag.domain.model.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/** 当前权限与真实定位信息测试，不把逻辑分页或未裁剪原文当作证据。 */
class EmployeeEvidenceServiceTest {
    private final EmployeeWorkspaceAccess access = mock(EmployeeWorkspaceAccess.class);
    private final DocumentMapper documents = mock(DocumentMapper.class);
    private final DocumentVersionMapper versions = mock(DocumentVersionMapper.class);
    private final DocumentAclService acl = mock(DocumentAclService.class);
    private final EmployeeEvidenceService evidence = new EmployeeEvidenceService(access, documents, versions, acl);
    private final UserPrincipal employee = new UserPrincipal("user_a", "tenant_a", "employee", "员工", UserSource.LOCAL,
            Set.of(), Set.of("role_new"), Set.of("app:use"), true, Set.of());
    private Document document;
    private DocumentVersion version;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.register(Document.class, DocumentVersion.class);
        document = new Document();
        document.setDocId("doc_a");
        document.setKbId("kb_a");
        document.setFileName("制度.pdf");
        document.setFileExt("pdf");
        document.setPublishStatus(PublishStatus.PUBLISHED);
        document.setUpdatedAt(LocalDateTime.now());
        version = new DocumentVersion();
        version.setDocId("doc_a");
        version.setVersionId("dv_a");
        version.setVersion("1.0");
        version.setCreatedAt(LocalDateTime.now().minusDays(1));
        when(documents.selectList(any())).thenAnswer(call -> List.of(document));
        when(versions.selectList(any())).thenAnswer(call -> List.of(version));
        when(access.accessibleKnowledgeBases(employee, Set.of("kb_a"))).thenReturn(Set.of("kb_a"));
        when(acl.trimRestricted(anyString(), anyList())).thenAnswer(call -> {
            assertSame(employee, UserContextHolder.get());
            return call.getArgument(1);
        });
    }

    @AfterEach
    void clear() {
        UserContextHolder.clear();
    }

    @Test
    void shouldCaptureOnlyReturnedPassageAndReliablePdfCoordinates() {
        var citation = evidence.capture(employee, List.of(node(Map.of("page_no", "3", "chunk_seq", 7, "title", "申请材料")))).get(0);
        assertEquals("已裁剪的证据", citation.content());
        assertEquals(3, citation.pageNo());
        assertEquals(8, citation.chunkOrdinal());
        assertEquals("申请材料", citation.chunkTitle());
        assertEquals("1.0", citation.documentVersion());
        assertEquals(version.getCreatedAt(), citation.versionCreatedAt());
        assertNull(UserContextHolder.get());
    }

    @Test
    void shouldNotExposeLogicalPagesOrMalformedCoordinatesAsPhysicalPages() {
        document.setFileExt("docx");
        assertNull(evidence.capture(employee, List.of(node(Map.of("page_no", 1)))).get(0).pageNo());
        document.setFileExt("pdf");
        for (Object malformed : List.of("-1", "0", "1.5", "unknown", 1_000_001)) {
            assertNull(evidence.capture(employee, List.of(node(Map.of("page_no", malformed)))).get(0).pageNo());
        }
    }

    @Test
    void shouldRecheckAclAndRestorePreviousThreadIdentityEvenWhenRejected() {
        UserPrincipal previous = new UserPrincipal("old", "old", "old", "旧身份", UserSource.LOCAL,
                Set.of(), Set.of("role_old"), Set.of(), true, Set.of());
        UserContextHolder.set(previous);
        assertTrue(evidence.canReadAll(employee, List.of(citation())));
        assertSame(previous, UserContextHolder.get());
        doAnswer(call -> {
            assertSame(employee, UserContextHolder.get());
            return List.of();
        }).when(acl).trimRestricted(anyString(), anyList());
        assertFalse(evidence.canReadAll(employee, List.of(citation().asInherited())));
        assertSame(previous, UserContextHolder.get());
        assertThrows(BizException.class, () -> evidence.capture(employee, List.of(node(Map.of()))));
        assertSame(previous, UserContextHolder.get());
    }

    @Test
    void shouldHideSourcesWhenKnowledgeBaseVersionOrDocumentOwnershipChanges() {
        when(access.accessibleKnowledgeBases(employee, Set.of("kb_a"))).thenReturn(Set.of());
        assertFalse(evidence.canReadAll(employee, List.of(citation())));
        when(access.accessibleKnowledgeBases(employee, Set.of("kb_a"))).thenReturn(Set.of("kb_a"));
        version.setDocId("doc_foreign");
        assertFalse(evidence.canReadAll(employee, List.of(citation())));
        version.setDocId("doc_a");
        document.setKbId("kb_foreign");
        assertFalse(evidence.canReadAll(employee, List.of(citation())));
    }

    @Test
    void shouldHideRemovedUnpublishedOrExpiredMaterialImmediately() {
        var sources = List.of(citation());
        document.setTrashed(1);
        assertFalse(evidence.canReadAll(employee, sources));
        document.setTrashed(0);
        document.setPublishStatus(PublishStatus.DRAFT);
        assertFalse(evidence.canReadAll(employee, sources));
        document.setPublishStatus(PublishStatus.PUBLISHED);
        document.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        assertFalse(evidence.canReadAll(employee, sources));
        document.setExpiresAt(null);
        when(documents.selectList(any())).thenReturn(List.of());
        assertFalse(evidence.canReadAll(employee, sources));
        when(documents.selectList(any())).thenReturn(List.of(document));
        when(versions.selectList(any())).thenReturn(List.of());
        assertFalse(evidence.canReadAll(employee, sources));
    }

    private RetrievalNodeView node(Map<String, Object> metadata) {
        return RetrievalNodeView.builder().docId("doc_a").documentVersionId("dv_a").chunkId("chunk_a")
                .content("已裁剪的证据").metadata(metadata).imageUrls(List.of("https://expired.invalid/presigned"))
                .previewUrl("https://expired.invalid/preview").build();
    }

    private EmployeeCitation citation() {
        return new EmployeeCitation("doc_a", "dv_a", "chunk_a", "kb_a", "制度.pdf", "1.0",
                null, null, null, null, null, "原文", false);
    }
}
