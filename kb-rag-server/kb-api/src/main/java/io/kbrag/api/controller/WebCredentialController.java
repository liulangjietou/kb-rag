package io.kbrag.api.controller;

import io.kbrag.api.annotation.AuditedOperation;
import io.kbrag.api.annotation.RequiresPermission;
import io.kbrag.api.dto.CreateWebCredentialRequest;
import io.kbrag.api.dto.UpdateWebCredentialRequest;
import io.kbrag.api.dto.WebCredentialResponse;
import io.kbrag.app.websource.WebCredentialService;
import io.kbrag.common.api.Result;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.constant.PermissionCodes;
import io.kbrag.domain.enums.WebAuthType;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/**
 * Site credential endpoints of the web import, the M18 contract.
 *
 * <p>Guarded by {@code system:config}, not {@code kb:write}: a credential is site level global
 * configuration - it applies to every knowledge base that registers a URL of the host - so it
 * belongs to the operators who own system settings, not to knowledge base editors. Every mutation
 * is audited; the audit rows carry the host, never the secret.
 *
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/web-credentials")
@RequiredArgsConstructor
@RequiresPermission(PermissionCodes.SYSTEM_CONFIG)
public class WebCredentialController {

    private final WebCredentialService webCredentialService;

    /**
     * Lists every credential, newest first. No secret in any row, by construction of the DTO.
     *
     * @return credentials
     */
    @GetMapping
    public Result<List<WebCredentialResponse>> list() {
        return Result.success(webCredentialService.list().stream()
                .map(WebCredentialResponse::from)
                .toList());
    }

    /**
     * Creates a credential for a host.
     *
     * @param request credential payload
     * @return created credential, secret omitted
     */
    @PostMapping
    @AuditedOperation(module = "SYSTEM", action = "CREATE", targetType = "WEB_CREDENTIAL",
            targetId = "#request.host")
    public Result<WebCredentialResponse> create(@Valid @RequestBody CreateWebCredentialRequest request) {
        boolean enabled = request.enabled() == null || request.enabled();
        return Result.success(WebCredentialResponse.from(webCredentialService.create(
                request.host().trim(),
                parseType(request.authType()),
                request.username(),
                request.secret(),
                request.headerName(),
                enabled)));
    }

    /**
     * Updates a credential; absent fields keep their stored values, a blank secret keeps the
     * stored secret.
     *
     * @param credentialId business id
     * @param request      fields to change
     * @return updated credential, secret omitted
     */
    @PutMapping("/{credentialId}")
    @AuditedOperation(module = "SYSTEM", action = "UPDATE", targetType = "WEB_CREDENTIAL",
            targetId = "#credentialId")
    public Result<WebCredentialResponse> update(@PathVariable String credentialId,
                                                @Valid @RequestBody UpdateWebCredentialRequest request) {
        return Result.success(WebCredentialResponse.from(webCredentialService.update(
                credentialId,
                request.username(),
                request.secret(),
                request.headerName(),
                request.enabled())));
    }

    /**
     * Removes a credential for good.
     *
     * @param credentialId business id
     * @return empty success envelope
     */
    @DeleteMapping("/{credentialId}")
    @AuditedOperation(module = "SYSTEM", action = "DELETE", targetType = "WEB_CREDENTIAL",
            targetId = "#credentialId")
    public Result<Void> remove(@PathVariable String credentialId) {
        webCredentialService.remove(credentialId);
        return Result.success(null);
    }

    private WebAuthType parseType(String raw) {
        try {
            return WebAuthType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw BizException.invalidParam("认证类型仅支持 BASIC / HEADER");
        }
    }
}
