package io.kbrag.api.controller;

import io.kbrag.api.annotation.RequiresPermission;
import io.kbrag.api.dto.ChunkResponse;
import io.kbrag.api.dto.DocumentPreviewResponse;
import io.kbrag.api.dto.DocumentResponse;
import io.kbrag.api.dto.PageResponse;
import io.kbrag.api.dto.ReparseRequest;
import io.kbrag.app.auth.AccessGuard;
import io.kbrag.app.auth.KbScopeGuard;
import io.kbrag.app.document.DocumentPreviewService;
import io.kbrag.app.document.DocumentService;
import io.kbrag.app.governance.DocumentGovernanceService;
import io.kbrag.common.api.Result;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.constant.PermissionCodes;
import io.kbrag.domain.enums.ProcessStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;

/**
 * Document intake and inspection endpoints.
 *
 * <p>The endpoints keyed by a knowledge base assert its scope directly; the ones keyed by a document id
 * resolve the owning base first through {@link KbScopeGuard}, since otherwise knowing an id would be enough
 * to reach a document of a base the caller was never granted.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class DocumentController {

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;
    private static final String FIELD_VERSION_ID = "version_id";

    private final DocumentService documentService;
    private final DocumentPreviewService documentPreviewService;
    private final DocumentGovernanceService documentGovernanceService;
    private final KbScopeGuard kbScopeGuard;

    /**
     * Uploads a document and hands it over to the asynchronous pipeline.
     *
     * @param kbId target knowledge base
     * @param file uploaded file
     * @return document the upload landed on, with the version and the duplicate hints
     */
    @PostMapping("/api/v1/kb/{kbId}/documents")
    @RequiresPermission(PermissionCodes.DOC_WRITE)
    public Result<DocumentResponse> upload(@PathVariable String kbId,
                                           @RequestParam("file") MultipartFile file) {
        AccessGuard.requireKbAccess(kbId);
        if (file == null || file.isEmpty()) {
            throw BizException.invalidParam("file is required");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw BizException.invalidParam("unable to read the uploaded file");
        }
        return Result.success(DocumentResponse.from(
                documentService.upload(kbId, file.getOriginalFilename(), content)));
    }

    /**
     * Lists the documents of a knowledge base.
     *
     * @param kbId          knowledge base business id
     * @param processStatus optional processing state filter
     * @param page          one based page number
     * @param size          page size
     * @return paged documents
     */
    @GetMapping("/api/v1/kb/{kbId}/documents")
    @RequiresPermission(PermissionCodes.KB_READ)
    public Result<PageResponse<DocumentResponse>> list(
            @PathVariable String kbId,
            @RequestParam(name = "process_status", required = false) String processStatus,
            @RequestParam(name = "page", defaultValue = "" + DEFAULT_PAGE) long page,
            @RequestParam(name = "size", defaultValue = "" + DEFAULT_PAGE_SIZE) long size) {
        AccessGuard.requireKbAccess(kbId);
        return Result.success(PageResponse.from(
                documentService.list(kbId, parseStatus(processStatus), normalizePage(page), normalizeSize(size)),
                DocumentResponse::from));
    }

    /**
     * Lists the chunks of the active version of a document.
     *
     * @param docId document business id
     * @param page  one based page number
     * @param size  page size
     * @return paged chunks
     */
    @GetMapping("/api/v1/documents/{docId}/chunks")
    @RequiresPermission(PermissionCodes.KB_READ)
    public Result<PageResponse<ChunkResponse>> chunks(
            @PathVariable String docId,
            @RequestParam(name = "page", defaultValue = "" + DEFAULT_PAGE) long page,
            @RequestParam(name = "size", defaultValue = "" + DEFAULT_PAGE_SIZE) long size) {
        kbScopeGuard.requireDocumentAccess(docId);
        return Result.success(PageResponse.from(
                documentService.chunks(docId, normalizePage(page), normalizeSize(size)),
                ChunkResponse::from));
    }

    /**
     * Re-runs the pipeline for the latest version of a document.
     *
     * @param docId document business id
     * @return version that was resubmitted
     */
    @PostMapping("/api/v1/documents/{docId}/reindex")
    @RequiresPermission(PermissionCodes.DOC_WRITE)
    public Result<Map<String, String>> reindex(@PathVariable String docId) {
        kbScopeGuard.requireDocumentAccess(docId);
        return Result.success(Map.of(FIELD_VERSION_ID, documentService.reindex(docId)));
    }

    /**
     * Returns what a document would be indexed as, while it waits for a confirmation.
     *
     * @param docId document business id
     * @return preview with pre signed image URLs and the textual proxies
     */
    @GetMapping("/api/v1/documents/{docId}/preview")
    @RequiresPermission(PermissionCodes.KB_READ)
    public Result<DocumentPreviewResponse> preview(@PathVariable String docId) {
        kbScopeGuard.requireDocumentAccess(docId);
        return Result.success(DocumentPreviewResponse.from(documentPreviewService.preview(docId)));
    }

    /**
     * Confirms a document so the pipeline splits and indexes it.
     *
     * @param docId document business id
     * @return version that was resumed
     */
    @PostMapping("/api/v1/documents/{docId}/confirm")
    @RequiresPermission(PermissionCodes.DOC_WRITE)
    public Result<Map<String, String>> confirm(@PathVariable String docId) {
        kbScopeGuard.requireDocumentAccess(docId);
        return Result.success(Map.of(FIELD_VERSION_ID, documentPreviewService.confirm(docId)));
    }

    /**
     * Re-renders the preview, optionally under an experimental rule set that is not saved.
     *
     * @param docId   document business id
     * @param request optional cleaning rules to try
     * @return refreshed preview, in the same shape the preview endpoint returns
     */
    @PostMapping("/api/v1/documents/{docId}/reparse")
    @RequiresPermission(PermissionCodes.DOC_WRITE)
    public Result<DocumentPreviewResponse> reparse(@PathVariable String docId,
                                                  @RequestBody(required = false) ReparseRequest request) {
        kbScopeGuard.requireDocumentAccess(docId);
        return Result.success(DocumentPreviewResponse.from(documentPreviewService.reparse(docId,
                request == null ? null : request.cleanRules())));
    }

    /**
     * Moves a document into the recycle bin, out of retrieval but restorable.
     *
     * <p>The URL predates M11 and keeps its meaning of "delete this document" for every caller; what
     * changed is that the deletion is now reversible until the retention period runs out or an
     * explicit purge follows. Chunks, versions and engine copies stay untouched until then.
     *
     * @param docId document business id
     * @return empty success envelope
     */
    @DeleteMapping("/api/v1/documents/{docId}")
    @RequiresPermission(PermissionCodes.DOC_WRITE)
    public Result<Void> delete(@PathVariable String docId) {
        kbScopeGuard.requireDocumentAccess(docId);
        documentGovernanceService.trash(docId);
        return Result.success(null);
    }

    private ProcessStatus parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return ProcessStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw BizException.invalidParam("unknown process_status: " + value);
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
