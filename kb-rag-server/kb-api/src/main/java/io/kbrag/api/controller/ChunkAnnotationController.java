package io.kbrag.api.controller;

import io.kbrag.api.annotation.AuditedOperation;
import io.kbrag.api.annotation.RequiresPermission;
import io.kbrag.api.dto.ChunkResponse;
import io.kbrag.api.dto.MergeChunksRequest;
import io.kbrag.api.dto.SplitChunkRequest;
import io.kbrag.api.dto.ToggleChunkRequest;
import io.kbrag.api.dto.UpdateChunkRequest;
import io.kbrag.app.annotation.ChunkAnnotationService;
import io.kbrag.app.auth.KbResourceGuard;
import io.kbrag.common.api.Result;
import io.kbrag.domain.constant.PermissionCodes;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Chunk annotation endpoints of the annotation workbench.
 *
 * <p>The payload shape is checked by the bean validation annotations of the request records; whether the
 * chunks named in it can actually be merged or split is decided by the application service, which is the
 * only layer that can read the rows the answer depends on.
 *
 * <p>Only the first chunk of a merge is scope checked. The service refuses chunks that do not share a
 * document, so a merge spanning two knowledge bases is already impossible; checking the rest would buy
 * nothing and cost a query per chunk.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/chunks")
@RequiredArgsConstructor
@RequiresPermission(PermissionCodes.DOC_WRITE)
public class ChunkAnnotationController {

    private static final String FIELD_CHANGED_CHUNK_IDS = "changed_chunk_ids";
    private static final String FIELD_CHUNKS = "chunks";

    private final ChunkAnnotationService chunkAnnotationService;
    private final KbResourceGuard kbResourceGuard;

    /**
     * Replaces the text of a chunk, re-embeds it and overwrites it in both engines.
     *
     * @param chunkId chunk business id
     * @param request new text
     * @return updated chunk
     */
    @PutMapping("/{chunkId}")
    @AuditedOperation(module = "ANNOTATION", action = "EDIT", targetType = "CHUNK", targetId = "#chunkId")
    public Result<ChunkResponse> edit(@PathVariable String chunkId,
                                      @Valid @RequestBody UpdateChunkRequest request) {
        kbResourceGuard.requireChunkAccess(chunkId);
        return Result.success(ChunkResponse.from(chunkAnnotationService.edit(chunkId, request.content())));
    }

    /**
     * Flips the retrieval switch of a chunk and of its children.
     *
     * @param chunkId chunk business id
     * @param request new switch value
     * @return chunk ids whose flag actually changed
     */
    @PostMapping("/{chunkId}/toggle")
    @AuditedOperation(module = "ANNOTATION", action = "TOGGLE", targetType = "CHUNK", targetId = "#chunkId")
    public Result<Map<String, Object>> toggle(@PathVariable String chunkId,
                                              @Valid @RequestBody ToggleChunkRequest request) {
        kbResourceGuard.requireChunkAccess(chunkId);
        List<String> changed = chunkAnnotationService.toggle(chunkId, request.enabled());
        return Result.success(Map.of(FIELD_CHANGED_CHUNK_IDS, changed));
    }

    /**
     * Replaces consecutive chunks by their concatenation.
     *
     * @param request chunks to merge
     * @return chunk the merge produced
     */
    @PostMapping("/merge")
    @AuditedOperation(module = "ANNOTATION", action = "MERGE", targetType = "CHUNK")
    public Result<ChunkResponse> merge(@Valid @RequestBody MergeChunksRequest request) {
        kbResourceGuard.requireChunkAccess(request.chunkIds().get(0));
        return Result.success(ChunkResponse.from(chunkAnnotationService.merge(request.chunkIds())));
    }

    /**
     * Replaces one chunk by the parts its text is cut into.
     *
     * @param chunkId chunk business id
     * @param request character offsets to cut at
     * @return chunks the split produced, in order
     */
    @PostMapping("/{chunkId}/split")
    @AuditedOperation(module = "ANNOTATION", action = "SPLIT", targetType = "CHUNK", targetId = "#chunkId")
    public Result<Map<String, Object>> split(@PathVariable String chunkId,
                                             @Valid @RequestBody SplitChunkRequest request) {
        kbResourceGuard.requireChunkAccess(chunkId);
        List<ChunkResponse> parts = chunkAnnotationService.split(chunkId, request.splitOffsets()).stream()
                .map(ChunkResponse::from).toList();
        return Result.success(Map.of(FIELD_CHUNKS, parts));
    }
}
