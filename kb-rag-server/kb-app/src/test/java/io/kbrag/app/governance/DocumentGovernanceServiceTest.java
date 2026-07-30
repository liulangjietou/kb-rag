package io.kbrag.app.governance;

import io.kbrag.app.document.DocumentService;
import io.kbrag.app.index.ActiveVersionResolver;
import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.app.support.MybatisLambdaCache;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.entity.KnowledgeBase;
import io.kbrag.domain.enums.PublishStatus;
import io.kbrag.domain.mapper.DocumentMapper;
import io.kbrag.domain.mapper.KnowledgeBaseMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the governance state machine, the validity window, the recycle bin and the scheduled purge
 * of the M11 contract: every mutation must both flip the row and drop the visibility cache, because
 * the cache is the only place governance takes effect.
 *
 * @author owlzhangfq@gmail.com
 */
class DocumentGovernanceServiceTest {

    private static final String DOC_ID = "doc_1";
    private static final String OTHER_DOC_ID = "doc_2";
    private static final String KB_ID = "kb_1";

    private DocumentMapper documentMapper;
    private KnowledgeBaseMapper knowledgeBaseMapper;
    private DocumentService documentService;
    private KnowledgeBaseService knowledgeBaseService;
    private ActiveVersionResolver activeVersionResolver;
    private KbProperties properties;
    private DocumentGovernanceService service;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.register(Document.class, KnowledgeBase.class);
        documentMapper = mock(DocumentMapper.class);
        knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        documentService = mock(DocumentService.class);
        knowledgeBaseService = mock(KnowledgeBaseService.class);
        activeVersionResolver = mock(ActiveVersionResolver.class);
        properties = new KbProperties();
        service = new DocumentGovernanceService(documentMapper, knowledgeBaseMapper, documentService,
                knowledgeBaseService, activeVersionResolver, properties);
    }

    @Test
    void shouldSubmitADraftForReview() {
        given(document(PublishStatus.DRAFT));

        Document updated = service.submitReview(DOC_ID);

        assertEquals(PublishStatus.PENDING_REVIEW, updated.getPublishStatus());
        verify(documentMapper).updateById(updated);
        verify(activeVersionResolver).invalidate(KB_ID);
    }

    @Test
    void shouldLetARejectedDocumentBeResubmitted() {
        given(document(PublishStatus.REJECTED));

        assertEquals(PublishStatus.PENDING_REVIEW, service.submitReview(DOC_ID).getPublishStatus());
    }

    @Test
    void shouldRefuseToSubmitAPublishedDocument() {
        given(document(PublishStatus.PUBLISHED));

        assertThrows(BizException.class, () -> service.submitReview(DOC_ID));
        verify(documentMapper, never()).updateById(any(Document.class));
    }

    @Test
    void shouldTreatARowPredatingTheMigrationAsPublished() {
        // Rows written before V13 carry a null column; the contract says they are PUBLISHED, so they
        // must be refused exactly like an explicit PUBLISHED.
        given(document(null));

        assertThrows(BizException.class, () -> service.submitReview(DOC_ID));
    }

    @Test
    void shouldApproveAPendingDocumentAndClearTheOldRejectionNote() {
        Document document = given(document(PublishStatus.PENDING_REVIEW));
        document.setReviewNote("previous verdict");
        AtomicReference<String> sqlSet = captureUpdateSqlSet();

        Document updated = service.approve(DOC_ID);

        assertEquals(PublishStatus.PUBLISHED, updated.getPublishStatus());
        assertNull(updated.getReviewNote());
        // The note of the superseded rejection must be written as null, which updateById would skip.
        assertTrue(sqlSet.get().contains("review_note"));
        verify(activeVersionResolver).invalidate(KB_ID);
    }

    @Test
    void shouldRefuseToApproveADocumentNobodySubmitted() {
        given(document(PublishStatus.DRAFT));

        assertThrows(BizException.class, () -> service.approve(DOC_ID));
    }

    @Test
    void shouldRejectAPendingDocumentWithTheNote() {
        given(document(PublishStatus.PENDING_REVIEW));

        Document updated = service.reject(DOC_ID, "needs a source");

        assertEquals(PublishStatus.REJECTED, updated.getPublishStatus());
        assertEquals("needs a source", updated.getReviewNote());
        verify(documentMapper).updateById(updated);
        verify(activeVersionResolver).invalidate(KB_ID);
    }

    @Test
    void shouldRefuseToRejectAPublishedDocument() {
        // PUBLISHED is terminal: taking content offline is an expiry or a trash operation, never a
        // review rollback.
        given(document(PublishStatus.PUBLISHED));

        assertThrows(BizException.class, () -> service.reject(DOC_ID, "note"));
    }

    @Test
    void shouldSetTheValidityWindow() {
        given(document(PublishStatus.PUBLISHED));
        AtomicReference<String> sqlSet = captureUpdateSqlSet();
        LocalDateTime from = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 12, 31, 0, 0);

        Document updated = service.updateValidity(DOC_ID, from, to);

        assertEquals(from, updated.getEffectiveAt());
        assertEquals(to, updated.getExpiresAt());
        assertTrue(sqlSet.get().contains("effective_at"));
        assertTrue(sqlSet.get().contains("expires_at"));
        verify(activeVersionResolver).invalidate(KB_ID);
    }

    @Test
    void shouldClearTheWindowWhenBothBoundsAreAbsent() {
        Document document = given(document(PublishStatus.PUBLISHED));
        document.setEffectiveAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        document.setExpiresAt(LocalDateTime.of(2026, 12, 31, 0, 0));
        captureUpdateSqlSet();

        Document updated = service.updateValidity(DOC_ID, null, null);

        assertNull(updated.getEffectiveAt());
        assertNull(updated.getExpiresAt());
        verify(activeVersionResolver).invalidate(KB_ID);
    }

    @Test
    void shouldRefuseAWindowThatNeverOpens() {
        LocalDateTime instant = LocalDateTime.of(2026, 6, 1, 0, 0);

        assertThrows(BizException.class, () -> service.updateValidity(DOC_ID, instant, instant));
        verify(documentMapper, never()).update(any(), any());
    }

    @Test
    void shouldRefuseGovernanceOperationsInsideTheTrash() {
        Document document = given(document(PublishStatus.DRAFT));
        document.setTrashed(1);

        assertThrows(BizException.class, () -> service.submitReview(DOC_ID));
        assertThrows(BizException.class, () -> service.updateValidity(DOC_ID, null, null));
    }

    @Test
    void shouldMoveADocumentIntoTheTrash() {
        Document document = given(document(PublishStatus.PUBLISHED));

        service.trash(DOC_ID);

        assertEquals(1, document.getTrashed());
        assertNotNull(document.getTrashedAt());
        verify(documentMapper).updateById(document);
        verify(activeVersionResolver).invalidate(KB_ID);
        // The trash keeps chunks, versions and engine copies; only the purge may destroy them.
        verify(documentService, never()).delete(any());
    }

    @Test
    void shouldRefuseToTrashADocumentTwice() {
        Document document = given(document(PublishStatus.PUBLISHED));
        document.setTrashed(1);

        assertThrows(BizException.class, () -> service.trash(DOC_ID));
    }

    @Test
    void shouldTrashASelectionInOneGo() {
        Document first = document(PublishStatus.PUBLISHED);
        Document second = document(PublishStatus.PUBLISHED);
        second.setDocId(OTHER_DOC_ID);
        List<String> docIds = List.of(DOC_ID, OTHER_DOC_ID);
        when(documentService.requireAllInKb(KB_ID, docIds)).thenReturn(List.of(first, second));

        List<String> trashed = service.trashAll(KB_ID, docIds);

        assertEquals(docIds, trashed);
        assertEquals(1, first.getTrashed());
        assertEquals(1, second.getTrashed());
        // 缓存失效整批只做一次：治理的生效点是可见集合，不是单行
        verify(activeVersionResolver, times(1)).invalidate(KB_ID);
    }

    @Test
    void shouldSkipAlreadyTrashedRowsInsteadOfFailingTheWholeBatch() {
        Document alive = document(PublishStatus.PUBLISHED);
        Document gone = document(PublishStatus.PUBLISHED);
        gone.setDocId(OTHER_DOC_ID);
        gone.setTrashed(1);
        List<String> docIds = List.of(DOC_ID, OTHER_DOC_ID);
        when(documentService.requireAllInKb(KB_ID, docIds)).thenReturn(List.of(alive, gone));

        List<String> trashed = service.trashAll(KB_ID, docIds);

        // 勾选与提交之间隔着列表轮询，"其中一篇刚被别人删了"是并发而不是错误
        assertEquals(List.of(DOC_ID), trashed);
        verify(documentMapper, times(1)).updateById(any(Document.class));
    }

    @Test
    void shouldRefuseAWholeBatchThatReachesOutsideTheKnowledgeBase() {
        List<String> docIds = List.of(DOC_ID, OTHER_DOC_ID);
        when(documentService.requireAllInKb(KB_ID, docIds))
                .thenThrow(BizException.notFound("some documents do not belong to this knowledge base"));

        // 越权不该被部分执行：一个 id 越界，整批拒绝，一行都不能落
        assertThrows(BizException.class, () -> service.trashAll(KB_ID, docIds));
        verify(documentMapper, never()).updateById(any(Document.class));
    }

    @Test
    void shouldRestoreATrashedDocument() {
        Document document = given(document(PublishStatus.PUBLISHED));
        document.setTrashed(1);
        document.setTrashedAt(LocalDateTime.now());
        AtomicReference<String> sqlSet = captureUpdateSqlSet();

        Document updated = service.restore(DOC_ID);

        assertEquals(0, updated.getTrashed());
        assertNull(updated.getTrashedAt());
        // The timestamp must be written as null or the purge pass would still see the old one.
        assertTrue(sqlSet.get().contains("trashed_at"));
        verify(activeVersionResolver).invalidate(KB_ID);
    }

    @Test
    void shouldRefuseToRestoreADocumentThatIsNotTrashed() {
        given(document(PublishStatus.PUBLISHED));

        assertThrows(BizException.class, () -> service.restore(DOC_ID));
    }

    @Test
    void shouldPurgeOnlyFromInsideTheTrash() {
        given(document(PublishStatus.PUBLISHED));

        assertThrows(BizException.class, () -> service.purge(DOC_ID));
        verify(documentService, never()).delete(any());
    }

    @Test
    void shouldPurgeThroughTheHardDeleteChain() {
        Document document = given(document(PublishStatus.PUBLISHED));
        document.setTrashed(1);

        service.purge(DOC_ID);

        verify(documentService).delete(DOC_ID);
        verify(activeVersionResolver).invalidate(KB_ID);
    }

    @Test
    void shouldFlipTheReviewSwitchOfAKnowledgeBase() {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setKbId(KB_ID);
        when(knowledgeBaseService.require(KB_ID)).thenReturn(knowledgeBase);

        KnowledgeBase updated = service.updateGovernance(KB_ID, true);

        assertEquals(1, updated.getReviewRequired());
        verify(knowledgeBaseMapper).updateById(knowledgeBase);
    }

    @Test
    void shouldPurgeExpiredTrashInBatches() {
        properties.getGovernance().setTrashPurgeBatchSize(2);
        when(documentMapper.selectList(any()))
                .thenReturn(List.of(trashedDocument(DOC_ID), trashedDocument(OTHER_DOC_ID)))
                .thenReturn(List.of());

        assertEquals(2, service.purgeExpired());

        verify(documentService).delete(DOC_ID);
        verify(documentService).delete(OTHER_DOC_ID);
        verify(activeVersionResolver, times(2)).invalidate(KB_ID);
    }

    @Test
    void shouldContinuePastOneFailingDocument() {
        when(documentMapper.selectList(any()))
                .thenReturn(List.of(trashedDocument(DOC_ID), trashedDocument(OTHER_DOC_ID)))
                .thenReturn(List.of());
        doThrow(new IllegalStateException("engine down")).when(documentService).delete(DOC_ID);

        assertEquals(1, service.purgeExpired());

        verify(documentService).delete(OTHER_DOC_ID);
    }

    @Test
    void shouldStopWhenAWholeBatchFails() {
        // Purging deletes the rows it selected; if none were deleted the next iteration would reselect
        // and refail the same rows forever.
        when(documentMapper.selectList(any())).thenReturn(List.of(trashedDocument(DOC_ID)));
        doThrow(new IllegalStateException("engine down")).when(documentService).delete(DOC_ID);

        assertEquals(0, service.purgeExpired());

        verify(documentMapper, times(1)).selectList(any());
    }

    @Test
    void shouldSkipTheScheduledPassWhenDisabled() {
        properties.getGovernance().setTrashPurgeEnabled(false);

        service.scheduledPurge();

        verify(documentMapper, never()).selectList(any());
    }

    @Test
    void shouldSwallowAFailureOfTheScheduledPass() {
        when(documentMapper.selectList(any())).thenThrow(new IllegalStateException("db down"));

        assertDoesNotThrow(() -> service.scheduledPurge());
    }

    private Document given(Document document) {
        when(documentService.require(DOC_ID)).thenReturn(document);
        return document;
    }

    private Document document(PublishStatus status) {
        Document document = new Document();
        document.setId(1L);
        document.setDocId(DOC_ID);
        document.setKbId(KB_ID);
        document.setPublishStatus(status);
        document.setTrashed(0);
        return document;
    }

    private Document trashedDocument(String docId) {
        Document document = new Document();
        document.setDocId(docId);
        document.setKbId(KB_ID);
        document.setTrashed(1);
        document.setTrashedAt(LocalDateTime.now().minusDays(60));
        return document;
    }

    /**
     * Records the SET clause of the next wrapper based update, the only place a null write is visible.
     */
    private AtomicReference<String> captureUpdateSqlSet() {
        AtomicReference<String> sqlSet = new AtomicReference<>();
        when(documentMapper.update(any(), any())).thenAnswer(invocation -> {
            com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Document> wrapper =
                    invocation.getArgument(1);
            sqlSet.set(wrapper.getSqlSet());
            return 1;
        });
        return sqlSet;
    }
}
