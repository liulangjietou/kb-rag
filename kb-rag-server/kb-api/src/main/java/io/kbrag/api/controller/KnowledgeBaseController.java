package io.kbrag.api.controller;

import io.kbrag.api.annotation.AuditedOperation;
import io.kbrag.api.annotation.RequiresPermission;
import io.kbrag.api.dto.ChatImportConfirmRequest;
import io.kbrag.api.dto.ChatImportPreviewResponse;
import io.kbrag.api.dto.ConfirmDocumentsRequest;
import io.kbrag.api.dto.CreateKnowledgeBaseRequest;
import io.kbrag.api.dto.DocumentBatchRequest;
import io.kbrag.api.dto.KnowledgeBaseResponse;
import io.kbrag.api.dto.RebuildRequest;
import io.kbrag.api.dto.RebuildStatusResponse;
import io.kbrag.api.dto.UpdateIndexConfigRequest;
import io.kbrag.api.dto.UpdateKbGovernanceRequest;
import io.kbrag.app.auth.AccessGuard;
import io.kbrag.app.chat.ChatImportService;
import io.kbrag.app.document.DocumentPreviewService;
import io.kbrag.app.document.DocumentService;
import io.kbrag.app.governance.DocumentGovernanceService;
import io.kbrag.app.index.RebuildService;
import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.common.api.Result;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.constant.PermissionCodes;
import io.kbrag.domain.entity.KnowledgeBase;
import io.kbrag.domain.model.KbIndexConfig;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Knowledge base management endpoints.
 *
 * <p>Every endpoint naming an existing base asserts the caller's data scope covers it. The listing does not:
 * it is trimmed to the visible bases instead, because a picker that hides what it may not offer is the
 * useful behaviour, while a refusal would be one.
 *
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/kb")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private static final String FIELD_STALE_DOCUMENTS = "stale_documents";
    private static final String FIELD_FINGERPRINT = "current_config_fingerprint";
    private static final String FIELD_QUEUED_DOC_IDS = "queued_doc_ids";
    private static final String FIELD_CONFIRMED_DOC_IDS = "confirmed_doc_ids";
    private static final String FIELD_IMPORTED_DOC_IDS = "imported_doc_ids";
    private static final String FIELD_DELETED_DOC_IDS = "deleted_doc_ids";
    private static final String FIELD_REINDEXED_DOC_IDS = "reindexed_doc_ids";

    private final KnowledgeBaseService knowledgeBaseService;
    private final RebuildService rebuildService;
    private final DocumentService documentService;
    private final DocumentPreviewService documentPreviewService;
    private final ChatImportService chatImportService;
    private final DocumentGovernanceService documentGovernanceService;

    /**
     * Creates a knowledge base together with its physical indices and aliases.
     *
     * @param request creation payload
     * @return created knowledge base
     */
    @PostMapping
    @RequiresPermission(PermissionCodes.KB_WRITE)
    @AuditedOperation(module = "KB", action = "CREATE", targetType = "KNOWLEDGE_BASE",
            targetId = "#result.data.kbId")
    public Result<KnowledgeBaseResponse> create(@Valid @RequestBody CreateKnowledgeBaseRequest request) {
        KnowledgeBase created = knowledgeBaseService.create(request.name(), request.description());
        return Result.success(toResponse(created));
    }

    /**
     * Lists the knowledge bases the caller may see.
     *
     * @return knowledge bases, newest first
     */
    @GetMapping
    @RequiresPermission(PermissionCodes.KB_READ)
    public Result<List<KnowledgeBaseResponse>> list() {
        return Result.success(knowledgeBaseService.list().stream().map(this::toResponse).toList());
    }

    /**
     * Returns one knowledge base.
     *
     * @param kbId business identifier
     * @return knowledge base
     */
    @GetMapping("/{kbId}")
    @RequiresPermission(PermissionCodes.KB_READ)
    public Result<KnowledgeBaseResponse> get(@PathVariable String kbId) {
        AccessGuard.requireKbAccess(kbId);
        return Result.success(toResponse(knowledgeBaseService.require(kbId)));
    }

    /**
     * Replaces the index configuration and reports how many documents it invalidated.
     *
     * <p>Nothing is rebuilt here. The response tells the console how much work the change created so an
     * operator can decide when to pay for it, which is the whole reason the configuration change and
     * the rebuild are two endpoints rather than one.
     *
     * @param kbId    business identifier
     * @param request new configuration
     * @return number of stale documents and the recomputed fingerprint
     */
    @PutMapping("/{kbId}/index-config")
    @RequiresPermission(PermissionCodes.KB_WRITE)
    @AuditedOperation(module = "KB", action = "UPDATE_INDEX_CONFIG", targetType = "KNOWLEDGE_BASE",
            targetId = "#kbId")
    public Result<Map<String, Object>> updateIndexConfig(@PathVariable String kbId,
                                                         @Valid @RequestBody UpdateIndexConfigRequest request) {
        AccessGuard.requireKbAccess(kbId);
        KnowledgeBase knowledgeBase = knowledgeBaseService.require(kbId);
        KbIndexConfig config = request.toIndexConfig(knowledgeBaseService.indexConfigOf(knowledgeBase));
        int stale = knowledgeBaseService.updateIndexConfig(kbId, config, request.retrievalConfig());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put(FIELD_STALE_DOCUMENTS, stale);
        data.put(FIELD_FINGERPRINT, knowledgeBaseService.require(kbId).getCurrentConfigFingerprint());
        return Result.success(data);
    }

    /**
     * Rebuilds documents under the current configuration.
     *
     * @param kbId    business identifier
     * @param request optional document scope, absent meaning every stale document
     * @return queued document ids
     */
    @PostMapping("/{kbId}/rebuild")
    @RequiresPermission(PermissionCodes.KB_WRITE)
    @AuditedOperation(module = "KB", action = "REBUILD", targetType = "KNOWLEDGE_BASE", targetId = "#kbId")
    public Result<Map<String, Object>> rebuild(@PathVariable String kbId,
                                               @RequestBody(required = false) RebuildRequest request) {
        AccessGuard.requireKbAccess(kbId);
        List<String> queued = rebuildService.submit(kbId, request == null ? null : request.docIds());
        return Result.success(Map.of(FIELD_QUEUED_DOC_IDS, queued));
    }

    /**
     * Reports how far the knowledge base is from running entirely on the current configuration.
     *
     * <p>Separate from the rebuild submission on purpose: a rebuild survives the page that started it,
     * so the console has to be able to ask for the state rather than remember it. Counting is scoped to
     * the whole base, not one page of documents, because a stale document on page two is just as much
     * work as one on page one.
     *
     * @param kbId business identifier
     * @return stale, in progress and failed document counts
     */
    @GetMapping("/{kbId}/rebuild-status")
    @RequiresPermission(PermissionCodes.KB_READ)
    public Result<RebuildStatusResponse> rebuildStatus(@PathVariable String kbId) {
        AccessGuard.requireKbAccess(kbId);
        return Result.success(RebuildStatusResponse.from(rebuildService.status(kbId)));
    }

    /**
     * Confirms the documents of a knowledge base that are waiting for a parse confirmation.
     *
     * @param kbId    business identifier
     * @param request optional document scope, absent meaning every waiting document
     * @return confirmed document ids
     */
    @PostMapping("/{kbId}/documents/confirm")
    @RequiresPermission(PermissionCodes.DOC_WRITE)
    @AuditedOperation(module = "KB", action = "CONFIRM_DOCUMENTS", targetType = "KNOWLEDGE_BASE",
            targetId = "#kbId")
    public Result<Map<String, Object>> confirmDocuments(
            @PathVariable String kbId,
            @RequestBody(required = false) ConfirmDocumentsRequest request) {
        AccessGuard.requireKbAccess(kbId);
        List<String> confirmed = documentPreviewService.confirmAll(kbId,
                request == null ? null : request.docIds());
        return Result.success(Map.of(FIELD_CONFIRMED_DOC_IDS, confirmed));
    }

    /**
     * Moves the selected documents into the recycle bin.
     *
     * <p>作用域一次校验、审计一条记录，这是它相对于前端循环调用单篇删除的全部意义。列表里混进
     * 别的知识库的文档会让整批被拒；已经在回收站里的文档则被跳过，响应如实返回真正被删掉的那些。
     *
     * @param kbId    business identifier
     * @param request documents to delete
     * @return documents that moved into the recycle bin
     */
    @PostMapping("/{kbId}/documents/batch-delete")
    @RequiresPermission(PermissionCodes.DOC_WRITE)
    @AuditedOperation(module = "KB", action = "BATCH_DELETE_DOCUMENTS", targetType = "KNOWLEDGE_BASE",
            targetId = "#kbId")
    public Result<Map<String, Object>> batchDeleteDocuments(
            @PathVariable String kbId,
            @Valid @RequestBody DocumentBatchRequest request) {
        AccessGuard.requireKbAccess(kbId);
        List<String> deleted = documentGovernanceService.trashAll(kbId, request.docIds());
        return Result.success(Map.of(FIELD_DELETED_DOC_IDS, deleted));
    }

    /**
     * Re-runs the pipeline of the selected documents, exactly as the per row rebuild does.
     *
     * <p>与 {@link #rebuild(String, RebuildRequest)} 是两件事：那个按当前配置重建、复用已有的解析
     * 产物，用于配置变更后的追平；这个完整重跑解析与索引，是控制台每行那个"重建"按钮的批量版。
     *
     * @param kbId    business identifier
     * @param request documents to rerun
     * @return documents whose pipeline was resubmitted
     */
    @PostMapping("/{kbId}/documents/batch-reindex")
    @RequiresPermission(PermissionCodes.DOC_WRITE)
    @AuditedOperation(module = "KB", action = "BATCH_REINDEX_DOCUMENTS", targetType = "KNOWLEDGE_BASE",
            targetId = "#kbId")
    public Result<Map<String, Object>> batchReindexDocuments(
            @PathVariable String kbId,
            @Valid @RequestBody DocumentBatchRequest request) {
        AccessGuard.requireKbAccess(kbId);
        List<String> submitted = documentService.reindexAll(kbId, request.docIds());
        return Result.success(Map.of(FIELD_REINDEXED_DOC_IDS, submitted));
    }

    /**
     * Parses a chat export and reports what importing it would do, without writing anything.
     *
     * @param kbId           business identifier
     * @param file           chat export, csv or xlsx
     * @param mappingProfile column mapping profile, absent selects the configured default
     * @return match preview carrying the token the confirmation needs
     */
    @PostMapping("/{kbId}/chat-imports")
    @RequiresPermission(PermissionCodes.DOC_WRITE)
    public Result<ChatImportPreviewResponse> previewChatImport(
            @PathVariable String kbId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "mapping_profile", required = false) String mappingProfile) {
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
        return Result.success(ChatImportPreviewResponse.from(chatImportService.preview(kbId,
                file.getOriginalFilename(), content, mappingProfile)));
    }

    /**
     * Executes a staged chat import.
     *
     * @param kbId    business identifier
     * @param request token returned by the preview and the optional conversation subset
     * @return document ids that were created or given a new version
     */
    @PostMapping("/{kbId}/chat-imports/confirm")
    @RequiresPermission(PermissionCodes.DOC_WRITE)
    @AuditedOperation(module = "KB", action = "CHAT_IMPORT", targetType = "KNOWLEDGE_BASE", targetId = "#kbId")
    public Result<Map<String, Object>> confirmChatImport(
            @PathVariable String kbId,
            @Valid @RequestBody ChatImportConfirmRequest request) {
        AccessGuard.requireKbAccess(kbId);
        List<String> imported = chatImportService.confirm(kbId, request.uploadToken(), request.sessionIds());
        return Result.success(Map.of(FIELD_IMPORTED_DOC_IDS, imported));
    }

    /**
     * Flips the review switch of a knowledge base; only future uploads read it.
     *
     * @param kbId    business identifier
     * @param request governance payload
     * @return updated knowledge base
     */
    @PutMapping("/{kbId}/governance")
    @RequiresPermission(PermissionCodes.KB_WRITE)
    @AuditedOperation(module = "KB", action = "UPDATE_GOVERNANCE", targetType = "KNOWLEDGE_BASE",
            targetId = "#kbId")
    public Result<KnowledgeBaseResponse> updateGovernance(@PathVariable String kbId,
                                                          @Valid @RequestBody UpdateKbGovernanceRequest request) {
        AccessGuard.requireKbAccess(kbId);
        return Result.success(toResponse(
                documentGovernanceService.updateGovernance(kbId, request.reviewRequired())));
    }

    /**
     * Soft deletes a knowledge base, its documents and its chunks, and clears the search engines.
     *
     * @param kbId business identifier
     * @return empty success envelope
     */
    @DeleteMapping("/{kbId}")
    @RequiresPermission(PermissionCodes.KB_DELETE)
    @AuditedOperation(module = "KB", action = "DELETE", targetType = "KNOWLEDGE_BASE", targetId = "#kbId")
    public Result<Void> delete(@PathVariable String kbId) {
        AccessGuard.requireKbAccess(kbId);
        knowledgeBaseService.delete(kbId);
        return Result.success(null);
    }

    private KnowledgeBaseResponse toResponse(KnowledgeBase entity) {
        return KnowledgeBaseResponse.from(entity, knowledgeBaseService.indexConfigOf(entity),
                knowledgeBaseService.retrievalConfigOf(entity));
    }
}
