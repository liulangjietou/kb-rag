package io.kbrag.api.controller;

import io.kbrag.api.dto.ChatImportConfirmRequest;
import io.kbrag.api.dto.ChatImportPreviewResponse;
import io.kbrag.api.dto.ConfirmDocumentsRequest;
import io.kbrag.api.dto.CreateKnowledgeBaseRequest;
import io.kbrag.api.dto.KnowledgeBaseResponse;
import io.kbrag.api.dto.RebuildRequest;
import io.kbrag.api.dto.UpdateIndexConfigRequest;
import io.kbrag.app.chat.ChatImportService;
import io.kbrag.app.document.DocumentPreviewService;
import io.kbrag.app.index.RebuildService;
import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.common.api.Result;
import io.kbrag.common.exception.BizException;
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

    private final KnowledgeBaseService knowledgeBaseService;
    private final RebuildService rebuildService;
    private final DocumentPreviewService documentPreviewService;
    private final ChatImportService chatImportService;

    /**
     * Creates a knowledge base together with its physical indices and aliases.
     *
     * @param request creation payload
     * @return created knowledge base
     */
    @PostMapping
    public Result<KnowledgeBaseResponse> create(@Valid @RequestBody CreateKnowledgeBaseRequest request) {
        KnowledgeBase created = knowledgeBaseService.create(request.name(), request.description());
        return Result.success(toResponse(created));
    }

    /**
     * Lists every knowledge base.
     *
     * @return knowledge bases, newest first
     */
    @GetMapping
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
    public Result<KnowledgeBaseResponse> get(@PathVariable String kbId) {
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
    public Result<Map<String, Object>> updateIndexConfig(@PathVariable String kbId,
                                                         @Valid @RequestBody UpdateIndexConfigRequest request) {
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
    public Result<Map<String, Object>> rebuild(@PathVariable String kbId,
                                               @RequestBody(required = false) RebuildRequest request) {
        List<String> queued = rebuildService.submit(kbId, request == null ? null : request.docIds());
        return Result.success(Map.of(FIELD_QUEUED_DOC_IDS, queued));
    }

    /**
     * Confirms the documents of a knowledge base that are waiting for a parse confirmation.
     *
     * @param kbId    business identifier
     * @param request optional document scope, absent meaning every waiting document
     * @return confirmed document ids
     */
    @PostMapping("/{kbId}/documents/confirm")
    public Result<Map<String, Object>> confirmDocuments(
            @PathVariable String kbId,
            @RequestBody(required = false) ConfirmDocumentsRequest request) {
        List<String> confirmed = documentPreviewService.confirmAll(kbId,
                request == null ? null : request.docIds());
        return Result.success(Map.of(FIELD_CONFIRMED_DOC_IDS, confirmed));
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
    public Result<ChatImportPreviewResponse> previewChatImport(
            @PathVariable String kbId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "mapping_profile", required = false) String mappingProfile) {
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
    public Result<Map<String, Object>> confirmChatImport(
            @PathVariable String kbId,
            @Valid @RequestBody ChatImportConfirmRequest request) {
        List<String> imported = chatImportService.confirm(kbId, request.uploadToken(), request.sessionIds());
        return Result.success(Map.of(FIELD_IMPORTED_DOC_IDS, imported));
    }

    /**
     * Soft deletes a knowledge base, its documents and its chunks, and clears the search engines.
     *
     * @param kbId business identifier
     * @return empty success envelope
     */
    @DeleteMapping("/{kbId}")
    public Result<Void> delete(@PathVariable String kbId) {
        knowledgeBaseService.delete(kbId);
        return Result.success(null);
    }

    private KnowledgeBaseResponse toResponse(KnowledgeBase entity) {
        return KnowledgeBaseResponse.from(entity, knowledgeBaseService.indexConfigOf(entity));
    }
}
