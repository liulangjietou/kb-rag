package io.kbrag.api.controller;

import io.kbrag.api.dto.MemoryAddRequest;
import io.kbrag.api.dto.MemoryAddResponse;
import io.kbrag.api.dto.MemoryNodePageResponse;
import io.kbrag.api.dto.MemoryNodeResponse;
import io.kbrag.api.dto.MemoryNodeUpdateRequest;
import io.kbrag.api.dto.MemoryProfileResponse;
import io.kbrag.api.dto.MemorySearchRequest;
import io.kbrag.api.dto.MemorySearchResponse;
import io.kbrag.api.filter.MemoryKeyAuthFilter;
import io.kbrag.app.memory.MemoryApiService;
import io.kbrag.app.memory.MemoryKeyPrincipal;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.api.Result;
import io.kbrag.common.exception.BizException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The memory open API, the M19 contract: what a consuming agent calls with its memory key.
 *
 * <p>Authentication, the key's quota and the library binding are handled before this class by
 * {@link MemoryKeyAuthFilter}; the controller validates payload shape and hands the authenticated
 * principal down, because the library every operation touches is the principal's, never the
 * payload's.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/memory")
@RequiredArgsConstructor
public class MemoryOpenApiController {

    private final MemoryApiService memoryApiService;

    /**
     * AddMemory: extraction from a conversation, a verbatim write, or both.
     *
     * @param request     add payload
     * @param httpRequest current request, source of the authenticated caller
     * @return nodes written or revised, plus the profile when one was extracted
     */
    @PostMapping("/add")
    public Result<MemoryAddResponse> add(@Valid @RequestBody MemoryAddRequest request,
                                         HttpServletRequest httpRequest) {
        return Result.success(MemoryAddResponse.from(
                memoryApiService.add(principalOf(httpRequest), request.toCommand())));
    }

    /**
     * SearchMemory: semantic recall of the entity's live memories with its profiles alongside.
     *
     * @param request     search payload
     * @param httpRequest current request, source of the authenticated caller
     * @return recall result
     */
    @PostMapping("/search")
    public Result<MemorySearchResponse> search(@Valid @RequestBody MemorySearchRequest request,
                                               HttpServletRequest httpRequest) {
        return Result.success(MemorySearchResponse.from(
                memoryApiService.search(principalOf(httpRequest), request.toCommand())));
    }

    /**
     * ListMemory: pages the entity's stored nodes, newest first.
     *
     * @param userId      memory entity id
     * @param ruleId      restricts to one fragment rule, optional
     * @param pageNum     1-based page number
     * @param pageSize    page size
     * @param httpRequest current request, source of the authenticated caller
     * @return page of nodes
     */
    @GetMapping("/memory_nodes")
    public Result<MemoryNodePageResponse> listNodes(@RequestParam("user_id") String userId,
                                                    @RequestParam(value = "rule_id", required = false) String ruleId,
                                                    @RequestParam(value = "page_num", defaultValue = "1") int pageNum,
                                                    @RequestParam(value = "page_size", defaultValue = "10") int pageSize,
                                                    HttpServletRequest httpRequest) {
        requireUserId(userId);
        return Result.success(MemoryNodePageResponse.from(memoryApiService.listNodes(
                principalOf(httpRequest), userId, ruleId, pageNum, pageSize)));
    }

    /**
     * UpdateMemory: replaces a node's content and refreshes its search copy.
     *
     * @param nodeId      node business id
     * @param request     update payload
     * @param httpRequest current request, source of the authenticated caller
     * @return updated node
     */
    @PatchMapping("/memory_nodes/{nodeId}")
    public Result<MemoryNodeResponse> updateNode(@PathVariable String nodeId,
                                                 @Valid @RequestBody MemoryNodeUpdateRequest request,
                                                 HttpServletRequest httpRequest) {
        return Result.success(MemoryNodeResponse.from(memoryApiService.updateNode(
                principalOf(httpRequest), nodeId, request.getUserId(),
                request.getCustomContent(), request.getMetaData())));
    }

    /**
     * DeleteMemory: removes one node of one entity.
     *
     * @param nodeId      node business id
     * @param userId      memory entity id the node must belong to
     * @param httpRequest current request, source of the authenticated caller
     * @return empty result
     */
    @DeleteMapping("/memory_nodes/{nodeId}")
    public Result<Void> deleteNode(@PathVariable String nodeId,
                                   @RequestParam("user_id") String userId,
                                   HttpServletRequest httpRequest) {
        requireUserId(userId);
        memoryApiService.deleteNode(principalOf(httpRequest), nodeId, userId);
        return Result.success(null);
    }

    /**
     * GetUserProfile: the entity's profiles, initial values filled in for unfilled fields.
     *
     * @param userId      memory entity id
     * @param ruleId      restricts to one profile rule, optional
     * @param httpRequest current request, source of the authenticated caller
     * @return profile per rule
     */
    @GetMapping("/profiles")
    public Result<List<MemoryProfileResponse>> profiles(@RequestParam("user_id") String userId,
                                                        @RequestParam(value = "rule_id", required = false) String ruleId,
                                                        HttpServletRequest httpRequest) {
        requireUserId(userId);
        return Result.success(memoryApiService.profiles(principalOf(httpRequest), userId, ruleId)
                .stream().map(MemoryProfileResponse::from).toList());
    }

    /**
     * Reads the caller the filter authenticated.
     *
     * <p>Absent means the filter did not run, which can only happen if this controller is ever
     * mapped outside the memory API prefix; failing loudly is the only safe answer to that.
     *
     * @param request current request
     * @return authenticated caller
     */
    private MemoryKeyPrincipal principalOf(HttpServletRequest request) {
        Object principal = request.getAttribute(MemoryKeyAuthFilter.ATTR_PRINCIPAL);
        if (!(principal instanceof MemoryKeyPrincipal resolved)) {
            log.error("memory api reached without an authenticated key, errorCode={}, uri={}",
                    ErrorCode.INVALID_API_KEY, request.getRequestURI());
            throw new BizException(ErrorCode.INVALID_API_KEY, "Memory Key 鉴权未通过");
        }
        return resolved;
    }

    private void requireUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw BizException.invalidParam("user_id must not be blank");
        }
    }
}
