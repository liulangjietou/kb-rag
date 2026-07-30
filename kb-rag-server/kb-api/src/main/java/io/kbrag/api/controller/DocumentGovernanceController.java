package io.kbrag.api.controller;

import io.kbrag.api.annotation.AuditedOperation;
import io.kbrag.api.annotation.RequiresPermission;
import io.kbrag.api.dto.DocumentResponse;
import io.kbrag.api.dto.PageResponse;
import io.kbrag.api.dto.RejectDocumentRequest;
import io.kbrag.api.dto.UpdateValidityRequest;
import io.kbrag.app.auth.AccessGuard;
import io.kbrag.app.auth.KbScopeGuard;
import io.kbrag.app.governance.DocumentGovernanceService;
import io.kbrag.common.api.Result;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.constant.PermissionCodes;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

/**
 * Content governance endpoints of the console, the M11 contract section 2.2: the review state
 * machine, the validity window and the recycle bin.
 *
 * <p>The document delete endpoint itself lives in {@link DocumentController} because its URL
 * predates M11; only its semantics moved here, to a trash operation.
 *
 * <p>The submission is granted by {@code doc:write} and everything downstream of it by {@code doc:review}:
 * the point of the state machine is that the author of a version is not the account that admits it. The
 * recycle bin listing accepts either, because it is the screen both of them work from - a reviewer who
 * cannot open it has nowhere to restore from, and an author who cannot open it cannot see what was deleted.
 *
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequiredArgsConstructor
public class DocumentGovernanceController {

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;

    private final DocumentGovernanceService documentGovernanceService;
    private final KbScopeGuard kbScopeGuard;

    /**
     * Submits a draft or a rejected document for review.
     *
     * @param docId document business id
     * @return updated document
     */
    @PostMapping("/api/v1/documents/{docId}/submit-review")
    @RequiresPermission(PermissionCodes.DOC_WRITE)
    @AuditedOperation(module = "GOVERNANCE", action = "SUBMIT_REVIEW", targetType = "DOCUMENT",
            targetId = "#docId")
    public Result<DocumentResponse> submitReview(@PathVariable String docId) {
        kbScopeGuard.requireDocumentAccess(docId);
        return Result.success(DocumentResponse.from(documentGovernanceService.submitReview(docId)));
    }

    /**
     * Approves a pending document, admitting it to retrieval.
     *
     * @param docId document business id
     * @return updated document
     */
    @PostMapping("/api/v1/documents/{docId}/approve")
    @RequiresPermission(PermissionCodes.DOC_REVIEW)
    @AuditedOperation(module = "GOVERNANCE", action = "APPROVE", targetType = "DOCUMENT", targetId = "#docId")
    public Result<DocumentResponse> approve(@PathVariable String docId) {
        kbScopeGuard.requireDocumentAccess(docId);
        return Result.success(DocumentResponse.from(documentGovernanceService.approve(docId)));
    }

    /**
     * Rejects a pending document with a reason the author will see.
     *
     * @param docId   document business id
     * @param request rejection payload carrying the mandatory note
     * @return updated document
     */
    @PostMapping("/api/v1/documents/{docId}/reject")
    @RequiresPermission(PermissionCodes.DOC_REVIEW)
    @AuditedOperation(module = "GOVERNANCE", action = "REJECT", targetType = "DOCUMENT", targetId = "#docId")
    public Result<DocumentResponse> reject(@PathVariable String docId,
                                           @Valid @RequestBody RejectDocumentRequest request) {
        kbScopeGuard.requireDocumentAccess(docId);
        return Result.success(DocumentResponse.from(
                documentGovernanceService.reject(docId, request.note().trim())));
    }

    /**
     * Sets or clears the validity window of a document.
     *
     * @param docId   document business id
     * @param request window bounds, {@code null} bounds are cleared
     * @return updated document
     */
    @PutMapping("/api/v1/documents/{docId}/validity")
    @RequiresPermission(PermissionCodes.DOC_REVIEW)
    @AuditedOperation(module = "GOVERNANCE", action = "UPDATE_VALIDITY", targetType = "DOCUMENT",
            targetId = "#docId")
    public Result<DocumentResponse> updateValidity(@PathVariable String docId,
                                                   @RequestBody UpdateValidityRequest request) {
        kbScopeGuard.requireDocumentAccess(docId);
        return Result.success(DocumentResponse.from(documentGovernanceService.updateValidity(docId,
                parseTime(request.effectiveAt(), "effective_at"),
                parseTime(request.expiresAt(), "expires_at"))));
    }

    /**
     * Lists the recycle bin of a knowledge base, most recently trashed first.
     *
     * @param kbId knowledge base business id
     * @param page one based page number
     * @param size page size
     * @return paged trashed documents
     */
    @GetMapping("/api/v1/kb/{kbId}/trash")
    @RequiresPermission({PermissionCodes.DOC_REVIEW, PermissionCodes.DOC_WRITE})
    public Result<PageResponse<DocumentResponse>> listTrash(
            @PathVariable String kbId,
            @RequestParam(name = "page", defaultValue = "" + DEFAULT_PAGE) long page,
            @RequestParam(name = "size", defaultValue = "" + DEFAULT_PAGE_SIZE) long size) {
        AccessGuard.requireKbAccess(kbId);
        return Result.success(PageResponse.from(
                documentGovernanceService.listTrash(kbId, normalizePage(page), normalizeSize(size)),
                DocumentResponse::from));
    }

    /**
     * Restores a trashed document to exactly the state it was deleted in.
     *
     * @param docId document business id
     * @return updated document
     */
    @PostMapping("/api/v1/documents/{docId}/restore")
    @RequiresPermission(PermissionCodes.DOC_REVIEW)
    @AuditedOperation(module = "GOVERNANCE", action = "RESTORE", targetType = "DOCUMENT", targetId = "#docId")
    public Result<DocumentResponse> restore(@PathVariable String docId) {
        kbScopeGuard.requireDocumentAccess(docId);
        return Result.success(DocumentResponse.from(documentGovernanceService.restore(docId)));
    }

    /**
     * Irreversibly removes a trashed document, engine copies included.
     *
     * @param docId document business id
     * @return empty success envelope
     */
    @DeleteMapping("/api/v1/documents/{docId}/purge")
    @RequiresPermission(PermissionCodes.DOC_REVIEW)
    @AuditedOperation(module = "GOVERNANCE", action = "PURGE", targetType = "DOCUMENT", targetId = "#docId")
    public Result<Void> purge(@PathVariable String docId) {
        kbScopeGuard.requireDocumentAccess(docId);
        documentGovernanceService.purge(docId);
        return Result.success(null);
    }

    /**
     * Parses an ISO local date time bound.
     *
     * @param value request literal
     * @param field field name, for the rejection message
     * @return timestamp, {@code null} when the bound is absent
     */
    private LocalDateTime parseTime(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw BizException.invalidParam(field + " 需为 ISO 格式的日期时间，例如 2026-07-26T00:00:00");
        }
    }

    private long normalizePage(long page) {
        return page < DEFAULT_PAGE ? DEFAULT_PAGE : page;
    }

    private long normalizeSize(long size) {
        if (size < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
