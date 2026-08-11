package io.kbrag.api.controller;

import io.kbrag.api.annotation.AuditedOperation;
import io.kbrag.api.annotation.RequiresPermission;
import io.kbrag.api.dto.MemoryAppKeyCreateRequest;
import io.kbrag.api.dto.MemoryAppKeyIssuedResponse;
import io.kbrag.api.dto.MemoryAppKeyResponse;
import io.kbrag.api.dto.MemoryAppKeyStatusRequest;
import io.kbrag.api.dto.MemoryEntityPageResponse;
import io.kbrag.api.dto.MemoryFragmentRuleResponse;
import io.kbrag.api.dto.MemoryFragmentRuleUpsertRequest;
import io.kbrag.api.dto.MemoryLibraryDetailResponse;
import io.kbrag.api.dto.MemoryLibraryPageResponse;
import io.kbrag.api.dto.MemoryLibraryResponse;
import io.kbrag.api.dto.MemoryLibraryUpsertRequest;
import io.kbrag.api.dto.MemoryNodePageResponse;
import io.kbrag.api.dto.MemoryProfileResponse;
import io.kbrag.api.dto.MemoryProfileRuleResponse;
import io.kbrag.api.dto.MemoryProfileRuleUpsertRequest;
import io.kbrag.api.dto.MemorySearchRequest;
import io.kbrag.api.dto.MemorySearchResponse;
import io.kbrag.app.memory.MemoryAdminService;
import io.kbrag.app.memory.MemoryAppKeyService;
import io.kbrag.common.api.Result;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.constant.PermissionCodes;
import io.kbrag.domain.enums.MemoryAppKeyStatus;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Memory library administration endpoints of the console, the M19 contract.
 *
 * <p>Console counterpart of {@link MemoryOpenApiController}: this side creates what that side
 * consumes. Reads require {@code memory:read}, writes {@code memory:write}; the library id always
 * comes from the URL path and every service call filters on it, so a rule or key of another
 * library answers 404.
 *
 * <p>The permission codes answer "may this account touch memory libraries at all"; <em>which</em>
 * libraries it may touch is the tenant fence on {@code t_kb_memory_library}, enforced service side
 * through {@code MemoryLibraryGuard}. No ownership check is written here on purpose - one placed in
 * a controller only guards the paths somebody remembered to guard.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/memory-libraries")
@RequiredArgsConstructor
public class MemoryLibraryController {

    private final MemoryAdminService memoryAdminService;
    private final MemoryAppKeyService memoryAppKeyService;

    // ------------------------------------------------------------------ libraries

    /**
     * Pages libraries with their statistics.
     *
     * @param keyword optional name filter
     * @param page    1-based page number
     * @param size    page size
     * @return page of libraries
     */
    @GetMapping
    @RequiresPermission(PermissionCodes.MEMORY_READ)
    public Result<MemoryLibraryPageResponse> pageLibraries(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(MemoryLibraryPageResponse.from(
                memoryAdminService.pageLibraries(keyword, page, size)));
    }

    /**
     * Creates a library, seeding its built in default fragment rule.
     *
     * @param request name and description
     * @return created library
     */
    @PostMapping
    @RequiresPermission(PermissionCodes.MEMORY_WRITE)
    @AuditedOperation(module = "MEMORY", action = "CREATE_LIBRARY", targetType = "MEMORY_LIBRARY",
            targetId = "#result.data.libraryId")
    public Result<MemoryLibraryResponse> createLibrary(
            @Valid @RequestBody MemoryLibraryUpsertRequest request) {
        return Result.success(MemoryLibraryResponse.from(
                memoryAdminService.createLibrary(request.name(), request.description())));
    }

    /**
     * Loads one library with statistics and both rule lists.
     *
     * @param libraryId library business id
     * @return library detail
     */
    @GetMapping("/{libraryId}")
    @RequiresPermission(PermissionCodes.MEMORY_READ)
    public Result<MemoryLibraryDetailResponse> libraryDetail(@PathVariable String libraryId) {
        return Result.success(MemoryLibraryDetailResponse.from(
                memoryAdminService.libraryDetail(libraryId)));
    }

    /**
     * Renames a library or replaces its description.
     *
     * @param libraryId library business id
     * @param request   name and description
     * @return updated library
     */
    @PutMapping("/{libraryId}")
    @RequiresPermission(PermissionCodes.MEMORY_WRITE)
    @AuditedOperation(module = "MEMORY", action = "UPDATE_LIBRARY", targetType = "MEMORY_LIBRARY",
            targetId = "#libraryId")
    public Result<MemoryLibraryResponse> updateLibrary(@PathVariable String libraryId,
                                                       @Valid @RequestBody MemoryLibraryUpsertRequest request) {
        return Result.success(MemoryLibraryResponse.from(
                memoryAdminService.updateLibrary(libraryId, request.name(), request.description())));
    }

    /**
     * Deletes a library and everything inside it, irrecoverably.
     *
     * @param libraryId library business id
     * @return empty result
     */
    @DeleteMapping("/{libraryId}")
    @RequiresPermission(PermissionCodes.MEMORY_WRITE)
    @AuditedOperation(module = "MEMORY", action = "DELETE_LIBRARY", targetType = "MEMORY_LIBRARY",
            targetId = "#libraryId")
    public Result<Void> deleteLibrary(@PathVariable String libraryId) {
        memoryAdminService.deleteLibrary(libraryId);
        return Result.success(null);
    }

    // ------------------------------------------------------------------ fragment rules

    /**
     * Lists the fragment rules of one library.
     *
     * @param libraryId library business id
     * @return rules with node counts
     */
    @GetMapping("/{libraryId}/fragment-rules")
    @RequiresPermission(PermissionCodes.MEMORY_READ)
    public Result<List<MemoryFragmentRuleResponse>> listFragmentRules(@PathVariable String libraryId) {
        return Result.success(memoryAdminService.listFragmentRules(libraryId).stream()
                .map(MemoryFragmentRuleResponse::from).toList());
    }

    /**
     * Adds a fragment rule, at most 50 per library.
     *
     * @param libraryId library business id
     * @param request   rule payload
     * @return created rule
     */
    @PostMapping("/{libraryId}/fragment-rules")
    @RequiresPermission(PermissionCodes.MEMORY_WRITE)
    @AuditedOperation(module = "MEMORY", action = "CREATE_FRAGMENT_RULE", targetType = "MEMORY_RULE",
            targetId = "#result.data.ruleId")
    public Result<MemoryFragmentRuleResponse> createFragmentRule(@PathVariable String libraryId,
                                                                 @Valid @RequestBody MemoryFragmentRuleUpsertRequest request) {
        return Result.success(MemoryFragmentRuleResponse.from(
                memoryAdminService.createFragmentRule(libraryId, request.toCommand())));
    }

    /**
     * Edits a fragment rule.
     *
     * @param libraryId library business id
     * @param ruleId    rule business id
     * @param request   rule payload
     * @return updated rule
     */
    @PutMapping("/{libraryId}/fragment-rules/{ruleId}")
    @RequiresPermission(PermissionCodes.MEMORY_WRITE)
    @AuditedOperation(module = "MEMORY", action = "UPDATE_FRAGMENT_RULE", targetType = "MEMORY_RULE",
            targetId = "#ruleId")
    public Result<MemoryFragmentRuleResponse> updateFragmentRule(@PathVariable String libraryId,
                                                                 @PathVariable String ruleId,
                                                                 @Valid @RequestBody MemoryFragmentRuleUpsertRequest request) {
        return Result.success(MemoryFragmentRuleResponse.from(
                memoryAdminService.updateFragmentRule(libraryId, ruleId, request.toCommand())));
    }

    /**
     * Deletes a fragment rule and every memory it produced; the built in rule is refused.
     *
     * @param libraryId library business id
     * @param ruleId    rule business id
     * @return empty result
     */
    @DeleteMapping("/{libraryId}/fragment-rules/{ruleId}")
    @RequiresPermission(PermissionCodes.MEMORY_WRITE)
    @AuditedOperation(module = "MEMORY", action = "DELETE_FRAGMENT_RULE", targetType = "MEMORY_RULE",
            targetId = "#ruleId")
    public Result<Void> deleteFragmentRule(@PathVariable String libraryId,
                                           @PathVariable String ruleId) {
        memoryAdminService.deleteFragmentRule(libraryId, ruleId);
        return Result.success(null);
    }

    // ------------------------------------------------------------------ profile rules

    /**
     * Lists the profile rules of one library.
     *
     * @param libraryId library business id
     * @return rules with parsed fields
     */
    @GetMapping("/{libraryId}/profile-rules")
    @RequiresPermission(PermissionCodes.MEMORY_READ)
    public Result<List<MemoryProfileRuleResponse>> listProfileRules(@PathVariable String libraryId) {
        return Result.success(memoryAdminService.listProfileRules(libraryId).stream()
                .map(MemoryProfileRuleResponse::from).toList());
    }

    /**
     * Adds a profile rule, at most 50 per library and 50 fields per rule.
     *
     * @param libraryId library business id
     * @param request   rule payload
     * @return created rule
     */
    @PostMapping("/{libraryId}/profile-rules")
    @RequiresPermission(PermissionCodes.MEMORY_WRITE)
    @AuditedOperation(module = "MEMORY", action = "CREATE_PROFILE_RULE", targetType = "MEMORY_RULE",
            targetId = "#result.data.ruleId")
    public Result<MemoryProfileRuleResponse> createProfileRule(@PathVariable String libraryId,
                                                               @Valid @RequestBody MemoryProfileRuleUpsertRequest request) {
        return Result.success(MemoryProfileRuleResponse.from(
                memoryAdminService.createProfileRule(libraryId, request.toCommand())));
    }

    /**
     * Edits a profile rule.
     *
     * @param libraryId library business id
     * @param ruleId    rule business id
     * @param request   rule payload
     * @return updated rule
     */
    @PutMapping("/{libraryId}/profile-rules/{ruleId}")
    @RequiresPermission(PermissionCodes.MEMORY_WRITE)
    @AuditedOperation(module = "MEMORY", action = "UPDATE_PROFILE_RULE", targetType = "MEMORY_RULE",
            targetId = "#ruleId")
    public Result<MemoryProfileRuleResponse> updateProfileRule(@PathVariable String libraryId,
                                                               @PathVariable String ruleId,
                                                               @Valid @RequestBody MemoryProfileRuleUpsertRequest request) {
        return Result.success(MemoryProfileRuleResponse.from(
                memoryAdminService.updateProfileRule(libraryId, ruleId, request.toCommand())));
    }

    /**
     * Deletes a profile rule and every profile extracted under it.
     *
     * @param libraryId library business id
     * @param ruleId    rule business id
     * @return empty result
     */
    @DeleteMapping("/{libraryId}/profile-rules/{ruleId}")
    @RequiresPermission(PermissionCodes.MEMORY_WRITE)
    @AuditedOperation(module = "MEMORY", action = "DELETE_PROFILE_RULE", targetType = "MEMORY_RULE",
            targetId = "#ruleId")
    public Result<Void> deleteProfileRule(@PathVariable String libraryId,
                                          @PathVariable String ruleId) {
        memoryAdminService.deleteProfileRule(libraryId, ruleId);
        return Result.success(null);
    }

    // ------------------------------------------------------------------ entities, nodes, profiles

    /**
     * Pages the memory entities of one library.
     *
     * @param libraryId library business id
     * @param userId    optional exact entity filter
     * @param page      1-based page number
     * @param size      page size
     * @return page of entities
     */
    @GetMapping("/{libraryId}/entities")
    @RequiresPermission(PermissionCodes.MEMORY_READ)
    public Result<MemoryEntityPageResponse> pageEntities(@PathVariable String libraryId,
                                                         @RequestParam(value = "user_id", required = false) String userId,
                                                         @RequestParam(defaultValue = "1") int page,
                                                         @RequestParam(defaultValue = "20") int size) {
        return Result.success(MemoryEntityPageResponse.from(
                memoryAdminService.pageEntities(libraryId, userId, page, size)));
    }

    /**
     * Pages the nodes of one entity, expired included - the operator's view.
     *
     * @param libraryId library business id
     * @param userId    memory entity id
     * @param ruleId    optional fragment rule filter
     * @param page      1-based page number
     * @param size      page size
     * @return page of nodes
     */
    @GetMapping("/{libraryId}/nodes")
    @RequiresPermission(PermissionCodes.MEMORY_READ)
    public Result<MemoryNodePageResponse> pageNodes(@PathVariable String libraryId,
                                                    @RequestParam("user_id") String userId,
                                                    @RequestParam(value = "rule_id", required = false) String ruleId,
                                                    @RequestParam(defaultValue = "1") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        return Result.success(MemoryNodePageResponse.from(
                memoryAdminService.pageNodes(libraryId, userId, ruleId, page, size)));
    }

    /**
     * Deletes one node together with its search copy.
     *
     * @param libraryId library business id
     * @param nodeId    node business id
     * @return empty result
     */
    @DeleteMapping("/{libraryId}/nodes/{nodeId}")
    @RequiresPermission(PermissionCodes.MEMORY_WRITE)
    @AuditedOperation(module = "MEMORY", action = "DELETE_NODE", targetType = "MEMORY_NODE",
            targetId = "#nodeId")
    public Result<Void> deleteNode(@PathVariable String libraryId, @PathVariable String nodeId) {
        memoryAdminService.deleteNode(libraryId, nodeId);
        return Result.success(null);
    }

    /**
     * The entity's profiles, one per rule, initial values filled in.
     *
     * @param libraryId library business id
     * @param userId    memory entity id
     * @param ruleId    optional profile rule filter
     * @return profiles
     */
    @GetMapping("/{libraryId}/profiles")
    @RequiresPermission(PermissionCodes.MEMORY_READ)
    public Result<List<MemoryProfileResponse>> profiles(@PathVariable String libraryId,
                                                        @RequestParam("user_id") String userId,
                                                        @RequestParam(value = "rule_id", required = false) String ruleId) {
        return Result.success(memoryAdminService.profiles(libraryId, userId, ruleId).stream()
                .map(MemoryProfileResponse::from).toList());
    }

    /**
     * Runs the open API search pipeline from the console, for tuning recall parameters.
     *
     * @param libraryId library business id
     * @param request   search payload, same shape as the open API
     * @return recall result
     */
    @PostMapping("/{libraryId}/search-debug")
    @RequiresPermission(PermissionCodes.MEMORY_READ)
    public Result<MemorySearchResponse> searchDebug(@PathVariable String libraryId,
                                                    @Valid @RequestBody MemorySearchRequest request) {
        return Result.success(MemorySearchResponse.from(
                memoryAdminService.searchDebug(libraryId, request.toCommand())));
    }

    // ------------------------------------------------------------------ memory keys

    /**
     * Lists the memory keys of one library, display form only.
     *
     * @param libraryId library business id
     * @return keys without key material
     */
    @GetMapping("/{libraryId}/keys")
    @RequiresPermission(PermissionCodes.MEMORY_READ)
    public Result<List<MemoryAppKeyResponse>> listKeys(@PathVariable String libraryId) {
        return Result.success(memoryAppKeyService.listByLibrary(libraryId).stream()
                .map(MemoryAppKeyResponse::from).toList());
    }

    /**
     * Issues a memory key bound to this library; the plaintext appears in this response only.
     *
     * @param libraryId library business id
     * @param request   name and quota
     * @return issued key with its plaintext
     */
    @PostMapping("/{libraryId}/keys")
    @RequiresPermission(PermissionCodes.MEMORY_WRITE)
    @AuditedOperation(module = "MEMORY", action = "CREATE_KEY", targetType = "MEMORY_KEY",
            targetId = "#result.data.key.keyId")
    public Result<MemoryAppKeyIssuedResponse> issueKey(@PathVariable String libraryId,
                                                       @Valid @RequestBody MemoryAppKeyCreateRequest request) {
        return Result.success(MemoryAppKeyIssuedResponse.from(
                memoryAppKeyService.issue(libraryId, request.name(), request.qpsLimit())));
    }

    /**
     * Enables or disables a memory key.
     *
     * @param libraryId   library business id
     * @param memoryKeyId key business id
     * @param request     new status
     * @return empty result
     */
    @PutMapping("/{libraryId}/keys/{memoryKeyId}/status")
    @RequiresPermission(PermissionCodes.MEMORY_WRITE)
    @AuditedOperation(module = "MEMORY", action = "UPDATE_KEY_STATUS", targetType = "MEMORY_KEY",
            targetId = "#memoryKeyId")
    public Result<Void> updateKeyStatus(@PathVariable String libraryId,
                                        @PathVariable String memoryKeyId,
                                        @Valid @RequestBody MemoryAppKeyStatusRequest request) {
        memoryAppKeyService.updateStatus(libraryId, memoryKeyId, parseStatus(request.status()));
        return Result.success(null);
    }

    /**
     * Rotates a memory key; the old plaintext dies immediately, the new one appears once.
     *
     * @param libraryId   library business id
     * @param memoryKeyId key business id
     * @return rotated key with its new plaintext
     */
    @PostMapping("/{libraryId}/keys/{memoryKeyId}/rotate")
    @RequiresPermission(PermissionCodes.MEMORY_WRITE)
    @AuditedOperation(module = "MEMORY", action = "ROTATE_KEY", targetType = "MEMORY_KEY",
            targetId = "#memoryKeyId")
    public Result<MemoryAppKeyIssuedResponse> rotateKey(@PathVariable String libraryId,
                                                        @PathVariable String memoryKeyId) {
        return Result.success(MemoryAppKeyIssuedResponse.from(
                memoryAppKeyService.rotate(libraryId, memoryKeyId)));
    }

    /**
     * Deletes a memory key; the caller it was issued to is locked out immediately.
     *
     * @param libraryId   library business id
     * @param memoryKeyId key business id
     * @return empty result
     */
    @DeleteMapping("/{libraryId}/keys/{memoryKeyId}")
    @RequiresPermission(PermissionCodes.MEMORY_WRITE)
    @AuditedOperation(module = "MEMORY", action = "DELETE_KEY", targetType = "MEMORY_KEY",
            targetId = "#memoryKeyId")
    public Result<Void> deleteKey(@PathVariable String libraryId, @PathVariable String memoryKeyId) {
        memoryAppKeyService.delete(libraryId, memoryKeyId);
        return Result.success(null);
    }

    private MemoryAppKeyStatus parseStatus(String status) {
        try {
            return MemoryAppKeyStatus.from(status);
        } catch (IllegalArgumentException e) {
            throw BizException.invalidParam("status 仅支持 ENABLED 或 DISABLED");
        }
    }
}
