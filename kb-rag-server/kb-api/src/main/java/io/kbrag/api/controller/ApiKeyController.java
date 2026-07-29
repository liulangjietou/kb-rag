package io.kbrag.api.controller;

import io.kbrag.api.annotation.RequiresPermission;
import io.kbrag.api.dto.ApiKeyCreatedResponse;
import io.kbrag.api.dto.ApiKeyResponse;
import io.kbrag.api.dto.CreateApiKeyRequest;
import io.kbrag.api.dto.UpdateApiKeyScopeRequest;
import io.kbrag.api.dto.UpdateApiKeyStatusRequest;
import io.kbrag.app.openapi.ApiKeyService;
import io.kbrag.app.openapi.ApiRateLimiter;
import io.kbrag.common.api.Result;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.constant.PermissionCodes;
import io.kbrag.domain.entity.ApiKey;
import io.kbrag.domain.enums.ApiKeyStatus;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * API key management endpoints of the console, requirement section 4.8.
 *
 * <p>Creation and rotation are the only responses that carry key material, and they carry it exactly once.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/api-keys")
@RequiredArgsConstructor
@RequiresPermission(PermissionCodes.APIKEY_MANAGE)
public class ApiKeyController {

    private final ApiKeyService apiKeyService;
    private final ApiRateLimiter apiRateLimiter;

    /**
     * Mints a key and returns its plaintext once.
     *
     * @param request name, quota and authorisation scope
     * @return created key plus its plaintext
     */
    @PostMapping
    public Result<ApiKeyCreatedResponse> create(@Valid @RequestBody CreateApiKeyRequest request) {
        ApiKeyService.IssuedKey issued = apiKeyService.create(request.name(), request.qpsLimit(),
                request.appScope());
        return Result.success(ApiKeyCreatedResponse.from(issued, apiKeyService.scopeOf(issued.key())));
    }

    /**
     * Lists every key, newest first, without key material.
     *
     * @return keys
     */
    @GetMapping
    public Result<List<ApiKeyResponse>> list() {
        return Result.success(apiKeyService.list().stream()
                .map(key -> ApiKeyResponse.from(key, apiKeyService.scopeOf(key)))
                .toList());
    }

    /**
     * Enables or disables a key.
     *
     * <p>The rate limit bucket of the key is dropped as well, so an operator who disabled a key by mistake does
     * not additionally hand the caller a throttled bucket when re-enabling it.
     *
     * @param keyId   key business id
     * @param request new status
     * @return updated key
     */
    @PutMapping("/{keyId}/status")
    public Result<ApiKeyResponse> updateStatus(@PathVariable String keyId,
                                              @Valid @RequestBody UpdateApiKeyStatusRequest request) {
        ApiKey key = apiKeyService.updateStatus(keyId, parseStatus(request.status()));
        apiRateLimiter.forget(keyId);
        return Result.success(ApiKeyResponse.from(key, apiKeyService.scopeOf(key)));
    }

    /**
     * Replaces the authorisation scope of a key.
     *
     * @param keyId   key business id
     * @param request allowed application ids, empty authorises every application
     * @return updated key
     */
    @PutMapping("/{keyId}/scope")
    public Result<ApiKeyResponse> updateScope(@PathVariable String keyId,
                                             @Valid @RequestBody UpdateApiKeyScopeRequest request) {
        ApiKey key = apiKeyService.updateScope(keyId, request.appScope());
        return Result.success(ApiKeyResponse.from(key, apiKeyService.scopeOf(key)));
    }

    /**
     * Replaces the secret of a key; the previous one stops working immediately.
     *
     * @param keyId key business id
     * @return updated key plus its new plaintext
     */
    @PostMapping("/{keyId}/rotate")
    public Result<ApiKeyCreatedResponse> rotate(@PathVariable String keyId) {
        ApiKeyService.IssuedKey issued = apiKeyService.rotate(keyId);
        apiRateLimiter.forget(keyId);
        return Result.success(ApiKeyCreatedResponse.from(issued, apiKeyService.scopeOf(issued.key())));
    }

    /**
     * Deletes a key.
     *
     * @param keyId key business id
     * @return empty success envelope
     */
    @DeleteMapping("/{keyId}")
    public Result<Void> delete(@PathVariable String keyId) {
        apiKeyService.delete(keyId);
        apiRateLimiter.forget(keyId);
        return Result.success(null);
    }

    private ApiKeyStatus parseStatus(String status) {
        try {
            return ApiKeyStatus.from(status);
        } catch (IllegalArgumentException e) {
            throw BizException.invalidParam("status 仅支持 ENABLED 或 DISABLED");
        }
    }
}
