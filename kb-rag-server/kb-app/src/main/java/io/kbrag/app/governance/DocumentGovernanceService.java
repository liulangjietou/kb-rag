package io.kbrag.app.governance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.kbrag.app.document.DocumentService;
import io.kbrag.app.index.ActiveVersionResolver;
import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.entity.KnowledgeBase;
import io.kbrag.domain.enums.PublishStatus;
import io.kbrag.domain.mapper.DocumentMapper;
import io.kbrag.domain.mapper.KnowledgeBaseMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Content governance of documents, the M11 contract section 2.2: the review state machine, the
 * validity window and the recycle bin.
 *
 * <p><b>Every operation here is a database row flip plus one cache invalidation.</b> Nothing is ever
 * written into a search engine: the {@link ActiveVersionResolver} is the single gate all three
 * governance axes pass through, so publishing, expiring and trashing cost the same regardless of
 * corpus size, and restoring from the trash is instant because the engine copies never left.
 *
 * <p><b>The one irreversible operation is the purge.</b> It delegates to the pre-M11 hard delete
 * chain and is reachable only from inside the trash - two explicit steps before content is truly
 * gone, which is the entire point of having a recycle bin.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentGovernanceService {

    /** Value of {@code trashed} inside the recycle bin. */
    private static final int TRASHED = 1;

    /** Value of {@code trashed} outside the recycle bin. */
    private static final int NOT_TRASHED = 0;

    private final DocumentMapper documentMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final DocumentService documentService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final ActiveVersionResolver activeVersionResolver;
    private final KbProperties properties;

    /**
     * Submits a draft or a rejected document for review.
     *
     * @param docId document business id
     * @return updated document
     */
    public Document submitReview(String docId) {
        Document document = requireOutsideTrash(docId);
        PublishStatus status = statusOf(document);
        if (status != PublishStatus.DRAFT && status != PublishStatus.REJECTED) {
            throw BizException.invalidParam("当前发布状态 " + status + " 不能提交审核，仅草稿或被驳回的文档可提交");
        }
        document.setPublishStatus(PublishStatus.PENDING_REVIEW);
        documentMapper.updateById(document);
        activeVersionResolver.invalidate(document.getKbId());
        log.info("document submitted for review, docId={}, kbId={}", docId, document.getKbId());
        return document;
    }

    /**
     * Approves a pending document, admitting it to retrieval.
     *
     * @param docId document business id
     * @return updated document
     */
    public Document approve(String docId) {
        Document document = requirePending(docId);
        // The rejection note describes a verdict this approval supersedes, so it goes with it.
        documentMapper.update(null, new LambdaUpdateWrapper<Document>()
                .eq(Document::getId, document.getId())
                .set(Document::getPublishStatus, PublishStatus.PUBLISHED)
                .set(Document::getReviewNote, null));
        document.setPublishStatus(PublishStatus.PUBLISHED);
        document.setReviewNote(null);
        activeVersionResolver.invalidate(document.getKbId());
        log.info("document approved, docId={}, kbId={}", docId, document.getKbId());
        return document;
    }

    /**
     * Rejects a pending document with a reason the author will see.
     *
     * @param docId document business id
     * @param note  rejection reason, mandatory
     * @return updated document
     */
    public Document reject(String docId, String note) {
        Document document = requirePending(docId);
        document.setPublishStatus(PublishStatus.REJECTED);
        document.setReviewNote(note);
        documentMapper.updateById(document);
        activeVersionResolver.invalidate(document.getKbId());
        log.info("document rejected, docId={}, kbId={}", docId, document.getKbId());
        return document;
    }

    /**
     * Sets or clears the validity window of a document.
     *
     * <p>An {@code expires_at} in the past is deliberately allowed: "take this offline right now" is
     * the most common reason an operator opens the dialog at all.
     *
     * @param docId       document business id
     * @param effectiveAt lower bound, {@code null} clears it
     * @param expiresAt   upper bound, {@code null} clears it
     * @return updated document
     */
    public Document updateValidity(String docId, LocalDateTime effectiveAt, LocalDateTime expiresAt) {
        if (effectiveAt != null && expiresAt != null && !effectiveAt.isBefore(expiresAt)) {
            throw BizException.invalidParam("生效时间必须早于失效时间");
        }
        Document document = requireOutsideTrash(docId);
        documentMapper.update(null, new LambdaUpdateWrapper<Document>()
                .eq(Document::getId, document.getId())
                .set(Document::getEffectiveAt, effectiveAt)
                .set(Document::getExpiresAt, expiresAt));
        document.setEffectiveAt(effectiveAt);
        document.setExpiresAt(expiresAt);
        activeVersionResolver.invalidate(document.getKbId());
        log.info("document validity updated, docId={}, kbId={}, effectiveAt={}, expiresAt={}",
                docId, document.getKbId(), effectiveAt, expiresAt);
        return document;
    }

    /**
     * Moves a document into the recycle bin, out of retrieval but restorable.
     *
     * <p>Chunks, versions and both engine copies stay untouched - the visibility set alone keeps the
     * document out of every recall, which is what makes {@link #restore(String)} an instant flag flip.
     *
     * @param docId document business id
     */
    public void trash(String docId) {
        Document document = documentService.require(docId);
        if (inTrash(document)) {
            throw BizException.invalidParam("文档已在回收站中");
        }
        document.setTrashed(TRASHED);
        document.setTrashedAt(LocalDateTime.now());
        documentMapper.updateById(document);
        activeVersionResolver.invalidate(document.getKbId());
        log.info("document trashed, docId={}, kbId={}", docId, document.getKbId());
    }

    /**
     * Restores a trashed document to exactly the state it was deleted in.
     *
     * @param docId document business id
     * @return updated document
     */
    public Document restore(String docId) {
        Document document = requireInTrash(docId);
        documentMapper.update(null, new LambdaUpdateWrapper<Document>()
                .eq(Document::getId, document.getId())
                .set(Document::getTrashed, NOT_TRASHED)
                .set(Document::getTrashedAt, null));
        document.setTrashed(NOT_TRASHED);
        document.setTrashedAt(null);
        activeVersionResolver.invalidate(document.getKbId());
        log.info("document restored from trash, docId={}, kbId={}", docId, document.getKbId());
        return document;
    }

    /**
     * Irreversibly removes a trashed document through the pre-M11 hard delete chain.
     *
     * <p>Only a trashed document may be purged: requiring the trash step first is the two-step
     * confirmation that keeps a mistyped identifier from destroying content.
     *
     * @param docId document business id
     */
    @Transactional(rollbackFor = Exception.class)
    public void purge(String docId) {
        Document document = requireInTrash(docId);
        documentService.delete(docId);
        activeVersionResolver.invalidate(document.getKbId());
        log.info("document purged from trash, docId={}, kbId={}", docId, document.getKbId());
    }

    /**
     * Lists the recycle bin of a knowledge base, most recently trashed first.
     *
     * @param kbId knowledge base business id
     * @param page one based page number
     * @param size page size
     * @return page of trashed documents
     */
    public IPage<Document> listTrash(String kbId, long page, long size) {
        return documentMapper.selectPage(new Page<>(page, size), new LambdaQueryWrapper<Document>()
                .eq(Document::getKbId, kbId)
                .eq(Document::getTrashed, TRASHED)
                .orderByDesc(Document::getTrashedAt)
                .orderByDesc(Document::getId));
    }

    /**
     * Flips the review switch of a knowledge base.
     *
     * <p>Only future uploads read the switch; documents already present keep their state, because a
     * policy change is not a verdict about content that was admitted under the old policy.
     *
     * @param kbId           knowledge base business id
     * @param reviewRequired {@code true} makes new documents start as DRAFT
     * @return updated knowledge base
     */
    public KnowledgeBase updateGovernance(String kbId, boolean reviewRequired) {
        KnowledgeBase knowledgeBase = knowledgeBaseService.require(kbId);
        knowledgeBase.setReviewRequired(reviewRequired ? 1 : 0);
        knowledgeBaseMapper.updateById(knowledgeBase);
        log.info("knowledge base governance updated, kbId={}, reviewRequired={}", kbId, reviewRequired);
        return knowledgeBase;
    }

    /**
     * Daily purge pass over the trash.
     *
     * <p>Failures are logged and never rethrown: the scheduler has no caller to report to, and a
     * skipped pass only defers the disk reclaim to the next one.
     */
    @Scheduled(cron = "${kb.governance.trash-purge-cron:0 10 4 * * *}")
    public void scheduledPurge() {
        if (!properties.getGovernance().isTrashPurgeEnabled()) {
            return;
        }
        try {
            int purged = purgeExpired();
            if (purged > 0) {
                log.info("trash purge pass finished, purgedDocuments={}", purged);
            }
        } catch (Exception e) {
            log.error("trash purge pass failed, errorCode={}", ErrorCode.INTERNAL_ERROR, e);
        }
    }

    /**
     * Purges every document trashed before the retention horizon, one bounded batch at a time.
     *
     * <p>Each document is purged in its own transaction: one failing document must not roll back the
     * neighbours already cleaned, and the failure is logged so the next pass retries it.
     *
     * @return documents purged by this pass
     */
    public int purgeExpired() {
        int batchSize = Math.max(1, properties.getGovernance().getTrashPurgeBatchSize());
        LocalDateTime horizon = LocalDate.now()
                .minusDays(properties.getGovernance().getTrashRetentionDays())
                .atStartOfDay();
        int purgedTotal = 0;
        while (true) {
            List<Document> batch = documentMapper.selectList(new LambdaQueryWrapper<Document>()
                    .eq(Document::getTrashed, TRASHED)
                    .lt(Document::getTrashedAt, horizon)
                    .orderByAsc(Document::getId)
                    .last("limit " + batchSize));
            if (CollectionUtils.isEmpty(batch)) {
                return purgedTotal;
            }
            int failed = 0;
            for (Document document : batch) {
                try {
                    documentService.delete(document.getDocId());
                    activeVersionResolver.invalidate(document.getKbId());
                    purgedTotal++;
                } catch (Exception e) {
                    failed++;
                    log.error("trash purge of one document failed, errorCode={}, docId={}",
                            ErrorCode.INTERNAL_ERROR, document.getDocId(), e);
                }
            }
            if (failed == batch.size()) {
                // Every document of the batch failed: the next iteration would reselect and refail the
                // same rows forever, so the pass stops and the next scheduled one tries again.
                return purgedTotal;
            }
        }
    }

    private Document requirePending(String docId) {
        Document document = requireOutsideTrash(docId);
        if (statusOf(document) != PublishStatus.PENDING_REVIEW) {
            throw BizException.invalidParam("当前发布状态 " + statusOf(document) + " 不在待审核中，无法执行审核操作");
        }
        return document;
    }

    private Document requireOutsideTrash(String docId) {
        Document document = documentService.require(docId);
        if (inTrash(document)) {
            throw BizException.invalidParam("文档在回收站中，请先恢复后再操作");
        }
        return document;
    }

    private Document requireInTrash(String docId) {
        Document document = documentService.require(docId);
        if (!inTrash(document)) {
            throw BizException.invalidParam("文档不在回收站中");
        }
        return document;
    }

    private boolean inTrash(Document document) {
        return document.getTrashed() != null && document.getTrashed() == TRASHED;
    }

    /**
     * Publication state of a row, PUBLISHED when the column predates M11 and is still {@code null}.
     */
    private PublishStatus statusOf(Document document) {
        return document.getPublishStatus() == null ? PublishStatus.PUBLISHED : document.getPublishStatus();
    }
}
