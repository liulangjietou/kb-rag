package io.kbrag.api.controller;

import io.kbrag.api.annotation.RequiresPermission;
import io.kbrag.api.dto.GraphEntityChunkResponse;
import io.kbrag.api.dto.GraphEntityResponse;
import io.kbrag.api.dto.GraphSummaryResponse;
import io.kbrag.api.dto.PageResponse;
import io.kbrag.api.dto.UpdateGraphConfigRequest;
import io.kbrag.app.auth.AccessGuard;
import io.kbrag.app.graph.GraphAdminService;
import io.kbrag.common.api.Result;
import io.kbrag.domain.constant.PermissionCodes;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Knowledge graph endpoints of one knowledge base, requirement section 4.9.
 *
 * <p>Reading the graph is granted by {@code kb:read} and flipping the switch by {@code kb:write}, but
 * the re-extraction is granted by {@code doc:write}: it rewrites every entity and relation derived from
 * the corpus, which is an edit of the indexed content rather than a change of configuration.
 *
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/kb/{kbId}/graph")
@RequiredArgsConstructor
public class GraphController {

    private static final String FIELD_GRAPH_ENABLED = "graph_enabled";
    private static final String FIELD_TASK_ID = "task_id";

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 200;

    /**
     * Source passages returned per entity drill down. The drill down is a review surface, not an export,
     * and a hub entity mentioned by ten thousand chunks would otherwise answer with the whole corpus.
     */
    private static final int MAX_ENTITY_CHUNKS = 100;

    private final GraphAdminService graphAdminService;

    /**
     * Flips the graph switch of a knowledge base.
     *
     * <p>Rejects the combination with weighted fusion here, through the same policy the knowledge base
     * configuration endpoint goes through: the console greys the option out, but the console is not what
     * makes a stored configuration valid.
     *
     * @param kbId    knowledge base business id
     * @param request new switch value
     * @return switch as it was stored
     */
    @PutMapping("/config")
    @RequiresPermission(PermissionCodes.KB_WRITE)
    public Result<Map<String, Object>> updateConfig(@PathVariable String kbId,
                                                    @Valid @RequestBody UpdateGraphConfigRequest request) {
        AccessGuard.requireKbAccess(kbId);
        graphAdminService.updateGraphEnabled(kbId, request.enabled());
        return Result.success(Map.of(FIELD_GRAPH_ENABLED, request.enabled()));
    }

    /**
     * Triggers a full re-extraction of the graph of a knowledge base.
     *
     * @param kbId knowledge base business id
     * @return business id of the task doing the work
     */
    @PostMapping("/extract")
    @RequiresPermission(PermissionCodes.DOC_WRITE)
    public Result<Map<String, Object>> extract(@PathVariable String kbId) {
        AccessGuard.requireKbAccess(kbId);
        return Result.success(Map.of(FIELD_TASK_ID, graphAdminService.triggerExtraction(kbId)));
    }

    /**
     * Reads the size of the graph and the state of its last extraction.
     *
     * @param kbId knowledge base business id
     * @return summary
     */
    @GetMapping("/summary")
    @RequiresPermission(PermissionCodes.KB_READ)
    public Result<GraphSummaryResponse> summary(@PathVariable String kbId) {
        AccessGuard.requireKbAccess(kbId);
        return Result.success(GraphSummaryResponse.from(graphAdminService.summary(kbId)));
    }

    /**
     * Lists the entities of a knowledge base, most traced first.
     *
     * @param kbId  knowledge base business id
     * @param query name filter, blank matches everything
     * @param page  one based page number
     * @param size  page size
     * @return page of entities
     */
    @GetMapping("/entities")
    @RequiresPermission(PermissionCodes.KB_READ)
    public Result<PageResponse<GraphEntityResponse>> entities(
            @PathVariable String kbId,
            @RequestParam(name = "query", required = false) String query,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size) {
        AccessGuard.requireKbAccess(kbId);
        int effectivePage = page == null || page < DEFAULT_PAGE ? DEFAULT_PAGE : page;
        int effectiveSize = size == null || size < DEFAULT_PAGE ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        List<GraphEntityResponse> items = graphAdminService
                .listEntities(kbId, query, effectivePage, effectiveSize).stream()
                .map(GraphEntityResponse::from).toList();
        return Result.success(new PageResponse<>(items, effectivePage, effectiveSize,
                graphAdminService.countEntities(kbId, query)));
    }

    /**
     * Reads the source passages of one entity.
     *
     * @param kbId       knowledge base business id
     * @param entityName exact entity name
     * @return source passages, at most {@value #MAX_ENTITY_CHUNKS}
     */
    @GetMapping("/entities/{entityName}/chunks")
    @RequiresPermission(PermissionCodes.KB_READ)
    public Result<List<GraphEntityChunkResponse>> entityChunks(@PathVariable String kbId,
                                                               @PathVariable String entityName) {
        AccessGuard.requireKbAccess(kbId);
        return Result.success(graphAdminService.chunksOf(kbId, entityName, MAX_ENTITY_CHUNKS).stream()
                .map(GraphEntityChunkResponse::from).toList());
    }
}
