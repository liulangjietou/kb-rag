package io.kbrag.api.controller;

import io.kbrag.api.dto.CreateKnowledgeBaseRequest;
import io.kbrag.api.dto.KnowledgeBaseResponse;
import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.common.api.Result;
import io.kbrag.domain.entity.KnowledgeBase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Knowledge base management endpoints.
 */
@RestController
@RequestMapping("/api/v1/kb")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    /**
     * Creates a knowledge base together with its physical indices and aliases.
     *
     * @param request creation payload
     * @return created knowledge base
     */
    @PostMapping
    public Result<KnowledgeBaseResponse> create(@Valid @RequestBody CreateKnowledgeBaseRequest request) {
        KnowledgeBase created = knowledgeBaseService.create(request.name(), request.description());
        return Result.success(KnowledgeBaseResponse.from(created));
    }

    /**
     * Lists every knowledge base.
     *
     * @return knowledge bases, newest first
     */
    @GetMapping
    public Result<List<KnowledgeBaseResponse>> list() {
        List<KnowledgeBaseResponse> items = knowledgeBaseService.list().stream()
                .map(KnowledgeBaseResponse::from)
                .toList();
        return Result.success(items);
    }

    /**
     * Returns one knowledge base.
     *
     * @param kbId business identifier
     * @return knowledge base
     */
    @GetMapping("/{kbId}")
    public Result<KnowledgeBaseResponse> get(@PathVariable String kbId) {
        return Result.success(KnowledgeBaseResponse.from(knowledgeBaseService.require(kbId)));
    }

    /**
     * Soft deletes a knowledge base and its documents.
     *
     * @param kbId business identifier
     * @return empty success envelope
     */
    @DeleteMapping("/{kbId}")
    public Result<Void> delete(@PathVariable String kbId) {
        knowledgeBaseService.delete(kbId);
        return Result.success(null);
    }
}
