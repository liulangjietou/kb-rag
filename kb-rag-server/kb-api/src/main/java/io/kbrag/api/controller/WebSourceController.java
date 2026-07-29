package io.kbrag.api.controller;

import io.kbrag.api.annotation.RequiresPermission;
import io.kbrag.api.dto.PageResponse;
import io.kbrag.api.dto.RegisterWebSourceRequest;
import io.kbrag.api.dto.UpdateWebSourceRequest;
import io.kbrag.api.dto.WebSourceResponse;
import io.kbrag.app.auth.AccessGuard;
import io.kbrag.app.auth.KbScopeGuard;
import io.kbrag.app.websource.WebSourceService;
import io.kbrag.common.api.Result;
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

/**
 * URL import endpoints of the console, the M12 contract section 3.4: register a page, watch its
 * sync outcomes, trigger a sync by hand, flip the switch, remove the registration.
 *
 * <p>Register and manual sync both run the fetch inline and return its outcome in the response -
 * the operator who just typed a URL wants to know right now whether it worked, not after the next
 * scheduled pass.
 *
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequiredArgsConstructor
public class WebSourceController {

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;

    private final WebSourceService webSourceService;
    private final KbScopeGuard kbScopeGuard;

    /**
     * Registers a URL and runs its first fetch immediately.
     *
     * @param kbId    knowledge base business id
     * @param request page address and the optional sync switch, on by default
     * @return registration carrying the outcome of the first fetch
     */
    @PostMapping("/api/v1/kb/{kbId}/web-sources")
    @RequiresPermission(PermissionCodes.DOC_WRITE)
    public Result<WebSourceResponse> register(@PathVariable String kbId,
                                              @Valid @RequestBody RegisterWebSourceRequest request) {
        AccessGuard.requireKbAccess(kbId);
        boolean syncEnabled = request.syncEnabled() == null || request.syncEnabled();
        return Result.success(WebSourceResponse.from(
                webSourceService.register(kbId, request.url().trim(), syncEnabled)));
    }

    /**
     * Lists the registrations of a knowledge base, most recently registered first.
     *
     * @param kbId knowledge base business id
     * @param page one based page number
     * @param size page size
     * @return paged registrations
     */
    @GetMapping("/api/v1/kb/{kbId}/web-sources")
    @RequiresPermission(PermissionCodes.KB_READ)
    public Result<PageResponse<WebSourceResponse>> list(
            @PathVariable String kbId,
            @RequestParam(name = "page", defaultValue = "" + DEFAULT_PAGE) long page,
            @RequestParam(name = "size", defaultValue = "" + DEFAULT_PAGE_SIZE) long size) {
        AccessGuard.requireKbAccess(kbId);
        return Result.success(PageResponse.from(
                webSourceService.list(kbId, normalizePage(page), normalizeSize(size)),
                WebSourceResponse::from));
    }

    /**
     * Runs one sync of one source on demand.
     *
     * @param sourceId registration business id
     * @return registration carrying the outcome of this fetch
     */
    @PostMapping("/api/v1/web-sources/{sourceId}/sync")
    @RequiresPermission(PermissionCodes.DOC_WRITE)
    public Result<WebSourceResponse> sync(@PathVariable String sourceId) {
        kbScopeGuard.requireWebSourceAccess(sourceId);
        return Result.success(WebSourceResponse.from(webSourceService.syncNow(sourceId)));
    }

    /**
     * Flips the scheduled-sync switch of one source.
     *
     * @param sourceId registration business id
     * @param request  new switch value
     * @return updated registration
     */
    @PutMapping("/api/v1/web-sources/{sourceId}")
    @RequiresPermission(PermissionCodes.DOC_WRITE)
    public Result<WebSourceResponse> update(@PathVariable String sourceId,
                                            @Valid @RequestBody UpdateWebSourceRequest request) {
        kbScopeGuard.requireWebSourceAccess(sourceId);
        return Result.success(WebSourceResponse.from(
                webSourceService.updateSyncEnabled(sourceId, request.syncEnabled())));
    }

    /**
     * Removes a registration; the document it fed stays in the knowledge base.
     *
     * @param sourceId registration business id
     * @return empty success envelope
     */
    @DeleteMapping("/api/v1/web-sources/{sourceId}")
    @RequiresPermission(PermissionCodes.DOC_WRITE)
    public Result<Void> remove(@PathVariable String sourceId) {
        kbScopeGuard.requireWebSourceAccess(sourceId);
        webSourceService.remove(sourceId);
        return Result.success(null);
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
