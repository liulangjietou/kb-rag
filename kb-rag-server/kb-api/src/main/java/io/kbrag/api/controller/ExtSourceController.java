package io.kbrag.api.controller;

import io.kbrag.api.annotation.RequiresPermission;
import io.kbrag.api.dto.ExtSourceItemResponse;
import io.kbrag.api.dto.ExtSourceResponse;
import io.kbrag.api.dto.ExtSourceSyncAcceptedResponse;
import io.kbrag.api.dto.ExtSourceTestResponse;
import io.kbrag.api.dto.PageResponse;
import io.kbrag.api.dto.RegisterExtSourceRequest;
import io.kbrag.api.dto.UpdateExtSourceRequest;
import io.kbrag.app.auth.KbResourceGuard;
import io.kbrag.app.extsource.ExtSourceCommand;
import io.kbrag.app.extsource.ExtSourceService;
import io.kbrag.common.api.Result;
import io.kbrag.domain.constant.PermissionCodes;
import io.kbrag.domain.entity.ExtSource;
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
 * External data source endpoints of the console, the M14 contract section 2.3: register a source,
 * watch its sync outcomes down to the object, trigger a scan, test the connection, edit, remove.
 *
 * <p>Register and manual sync hand the scan to the executor and return at once - a bucket holds an
 * unbounded number of objects, so unlike the single page fetch of the web import there is no
 * outcome a console request could reasonably wait for.
 *
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequiredArgsConstructor
public class ExtSourceController {

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;

    private final ExtSourceService extSourceService;
    private final KbResourceGuard kbResourceGuard;

    /**
     * Registers an external source and triggers its first scan asynchronously.
     *
     * @param kbId    knowledge base business id
     * @param request connection details and the optional sync switch, on by default
     * @return registration row; the first scan's outcome appears on it once the scan finishes
     */
    @PostMapping("/api/v1/kb/{kbId}/ext-sources")
    @RequiresPermission(PermissionCodes.DOC_WRITE)
    public Result<ExtSourceResponse> register(@PathVariable String kbId,
                                              @Valid @RequestBody RegisterExtSourceRequest request) {
        kbResourceGuard.requireKb(kbId);
        ExtSource source = extSourceService.register(kbId, new ExtSourceCommand(
                request.sourceType().trim(),
                request.name().trim(),
                request.endpoint().trim(),
                trimToNull(request.region()),
                request.bucket().trim(),
                trimToNull(request.prefix()),
                request.accessKey().trim(),
                request.secretKey(),
                request.syncEnabled()));
        extSourceService.syncAsync(source.getSourceId());
        return Result.success(ExtSourceResponse.from(source));
    }

    /**
     * Lists the sources of a knowledge base, most recently registered first.
     *
     * @param kbId knowledge base business id
     * @param page one based page number
     * @param size page size
     * @return paged sources, secret always masked
     */
    @GetMapping("/api/v1/kb/{kbId}/ext-sources")
    @RequiresPermission(PermissionCodes.KB_READ)
    public Result<PageResponse<ExtSourceResponse>> list(
            @PathVariable String kbId,
            @RequestParam(name = "page", defaultValue = "" + DEFAULT_PAGE) long page,
            @RequestParam(name = "size", defaultValue = "" + DEFAULT_PAGE_SIZE) long size) {
        kbResourceGuard.requireKb(kbId);
        return Result.success(PageResponse.from(
                extSourceService.list(kbId, normalizePage(page), normalizeSize(size)),
                ExtSourceResponse::from));
    }

    /**
     * Accepts one scan of one source; the outcome lands on the source and item rows.
     *
     * @param sourceId source business id
     * @return acceptance acknowledgement
     */
    @PostMapping("/api/v1/ext-sources/{sourceId}/sync")
    @RequiresPermission(PermissionCodes.DOC_WRITE)
    public Result<ExtSourceSyncAcceptedResponse> sync(@PathVariable String sourceId) {
        kbResourceGuard.requireExtSourceAccess(sourceId);
        extSourceService.ensureExists(sourceId);
        extSourceService.syncAsync(sourceId);
        return Result.success(ExtSourceSyncAcceptedResponse.of());
    }

    /**
     * Lists the per object sync outcomes of one source.
     *
     * @param sourceId source business id
     * @param page     one based page number
     * @param size     page size
     * @return paged item rows
     */
    @GetMapping("/api/v1/ext-sources/{sourceId}/items")
    @RequiresPermission(PermissionCodes.KB_READ)
    public Result<PageResponse<ExtSourceItemResponse>> items(
            @PathVariable String sourceId,
            @RequestParam(name = "page", defaultValue = "" + DEFAULT_PAGE) long page,
            @RequestParam(name = "size", defaultValue = "" + DEFAULT_PAGE_SIZE) long size) {
        kbResourceGuard.requireExtSourceAccess(sourceId);
        return Result.success(PageResponse.from(
                extSourceService.listItems(sourceId, normalizePage(page), normalizeSize(size)),
                ExtSourceItemResponse::from));
    }

    /**
     * Updates the connection details of one source; a blank secret keeps the stored one.
     *
     * @param sourceId source business id
     * @param request  new values
     * @return updated source, secret masked
     */
    @PutMapping("/api/v1/ext-sources/{sourceId}")
    @RequiresPermission(PermissionCodes.DOC_WRITE)
    public Result<ExtSourceResponse> update(@PathVariable String sourceId,
                                            @Valid @RequestBody UpdateExtSourceRequest request) {
        kbResourceGuard.requireExtSourceAccess(sourceId);
        return Result.success(ExtSourceResponse.from(extSourceService.update(sourceId, new ExtSourceCommand(
                null,
                request.name().trim(),
                request.endpoint().trim(),
                trimToNull(request.region()),
                request.bucket().trim(),
                trimToNull(request.prefix()),
                request.accessKey().trim(),
                request.secretKey(),
                request.syncEnabled()))));
    }

    /**
     * Probes connectivity and credentials of one source without touching any object.
     *
     * @param sourceId source business id
     * @return probe outcome
     */
    @PostMapping("/api/v1/ext-sources/{sourceId}/test")
    @RequiresPermission(PermissionCodes.DOC_WRITE)
    public Result<ExtSourceTestResponse> test(@PathVariable String sourceId) {
        kbResourceGuard.requireExtSourceAccess(sourceId);
        return Result.success(ExtSourceTestResponse.from(extSourceService.testConnection(sourceId)));
    }

    /**
     * Removes a source and its item rows; the documents they fed stay in the knowledge base.
     *
     * @param sourceId source business id
     * @return empty success envelope
     */
    @DeleteMapping("/api/v1/ext-sources/{sourceId}")
    @RequiresPermission(PermissionCodes.DOC_WRITE)
    public Result<Void> remove(@PathVariable String sourceId) {
        kbResourceGuard.requireExtSourceAccess(sourceId);
        extSourceService.remove(sourceId);
        return Result.success(null);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
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
