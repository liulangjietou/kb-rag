package io.kbrag.api.controller;

import io.kbrag.api.annotation.AuditedOperation;
import io.kbrag.api.annotation.RequiresPermission;
import io.kbrag.api.dto.IkDictRequest;
import io.kbrag.api.dto.IkDictResponse;
import io.kbrag.api.dto.IkDictStatusRequest;
import io.kbrag.api.dto.PageResponse;
import io.kbrag.app.dict.IkDictService;
import io.kbrag.common.api.Result;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.constant.PermissionCodes;
import io.kbrag.domain.enums.DictType;
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

/**
 * ik dictionary management endpoints.
 *
 * <p>Authenticated like every other management API. The dictionary the plugin downloads is served by a
 * separate, unauthenticated endpoint, because the tokenizer cannot present a credential.
 *
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/dict/ik")
@RequiredArgsConstructor
@RequiresPermission(PermissionCodes.PLATFORM_CONFIG)
public class IkDictController {

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;

    private final IkDictService ikDictService;

    /**
     * Lists dictionary entries.
     *
     * @param dictType optional dictionary kind filter
     * @param keyword  optional term prefix filter
     * @param page     one based page number
     * @param size     page size
     * @return paged entries
     */
    @GetMapping
    public Result<PageResponse<IkDictResponse>> list(
            @RequestParam(name = "dict_type", required = false) String dictType,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "page", defaultValue = "" + DEFAULT_PAGE) long page,
            @RequestParam(name = "size", defaultValue = "" + DEFAULT_PAGE_SIZE) long size) {
        return Result.success(PageResponse.from(
                ikDictService.list(parseType(dictType), keyword, normalizePage(page), normalizeSize(size)),
                IkDictResponse::from));
    }

    /**
     * Adds a term.
     *
     * @param request entry payload
     * @return created entry
     */
    @PostMapping
    @AuditedOperation(module = "DICT", action = "CREATE", targetType = "DICT_WORD",
            targetId = "#result.data.word")
    public Result<IkDictResponse> create(@Valid @RequestBody IkDictRequest request) {
        return Result.success(IkDictResponse.from(
                ikDictService.create(request.word(), request.resolvedType(), request.remark())));
    }

    /**
     * Switches a term on or off.
     *
     * @param word    dictionary term
     * @param request new availability
     * @return updated entry
     */
    @PutMapping("/{word}")
    @AuditedOperation(module = "DICT", action = "UPDATE_STATUS", targetType = "DICT_WORD", targetId = "#word")
    public Result<IkDictResponse> updateStatus(@PathVariable String word,
                                               @Valid @RequestBody IkDictStatusRequest request) {
        return Result.success(IkDictResponse.from(
                ikDictService.updateStatus(word, request.resolvedStatus())));
    }

    /**
     * Removes a term.
     *
     * @param word dictionary term
     * @return empty success envelope
     */
    @DeleteMapping("/{word}")
    @AuditedOperation(module = "DICT", action = "DELETE", targetType = "DICT_WORD", targetId = "#word")
    public Result<Void> delete(@PathVariable String word) {
        ikDictService.delete(word);
        return Result.success(null);
    }

    private DictType parseType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        DictType resolved = DictType.from(value);
        if (resolved == null) {
            throw BizException.invalidParam("unknown dict_type: " + value);
        }
        return resolved;
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
