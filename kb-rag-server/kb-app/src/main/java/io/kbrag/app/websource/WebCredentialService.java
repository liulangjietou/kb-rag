package io.kbrag.app.websource;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.entity.WebCredential;
import io.kbrag.domain.enums.WebAuthType;
import io.kbrag.domain.mapper.WebCredentialMapper;
import io.kbrag.domain.model.FetchCredential;
import io.kbrag.domain.service.BizIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Site credential management of the web import, the M18 contract.
 *
 * <p>One credential per host <i>per tenant</i>, resolved at fetch time by
 * {@link #resolveFor(String, String)}: the sync pass asks with the tenant of the base it is syncing
 * for and the host of the URL it is about to fetch, and receives the injectable header form, or
 * {@code null} when that tenant has no (enabled) credential for the site - the anonymous fetch of
 * M12 stays the default and the common case.
 *
 * <p><b>Two different mechanisms isolate the two sides of this service, and only one of them is
 * automatic.</b> The console side - {@link #list()}, {@link #create}, {@link #update},
 * {@link #remove} - never mentions a tenant: {@code t_kb_web_credential} is in
 * {@code KbTenantLineHandler.FENCED_TABLES} since V22, so every statement those methods issue is
 * fenced to the caller's tenant, a foreign credential simply is not there, and the duplicate-host
 * check narrows to "this tenant already has one" on its own. The fetch side gets no such help: it
 * runs on the scheduled sync thread, which carries no console principal, and the fence skips that
 * thread entirely by design. Which is why {@link #resolveFor(String, String)} takes a tenant and
 * writes the predicate itself, and why it refuses to query at all when it is not given one.
 *
 * <p><b>The secret never leaves this service in readable form.</b> List and update responses carry
 * everything but the secret; an update with a blank secret keeps the stored one, which is what lets
 * the console edit the enabled flag without re-typing the password.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebCredentialService {

    /** {@code enabled} value that lets fetches use the credential. */
    private static final int ENABLED = 1;

    /** {@code enabled} value that parks the credential without deleting it. */
    private static final int DISABLED = 0;

    private final WebCredentialMapper webCredentialMapper;
    private final BizIdGenerator bizIdGenerator;

    /**
     * Lists the calling tenant's credentials, newest first. Secrets ride along inside the entity but
     * the API layer maps them out; nothing here re-reads per row.
     *
     * @return the credential rows of the caller's tenant
     */
    public List<WebCredential> list() {
        return webCredentialMapper.selectList(new LambdaQueryWrapper<WebCredential>()
                .orderByDesc(WebCredential::getId));
    }

    /**
     * Creates a credential for a host, within the caller's tenant.
     *
     * <p>Neither the duplicate check nor the insert names a tenant: the fence adds the predicate to
     * the count and the column to the INSERT (the service never calls {@code setTenantId}, same as
     * {@code KnowledgeBaseService#create}). So "one credential per host" reads as "per host of this
     * tenant" since V22, which is the point - the other tenant's row for the same wiki is none of
     * this caller's business and must not block them from creating their own.
     *
     * @param host       exact host, lower cased on storage
     * @param authType   authentication scheme
     * @param username   BASIC username, ignored for HEADER
     * @param secret     BASIC password or full header value
     * @param headerName HEADER header name, ignored for BASIC
     * @param enabled    whether fetches may use it right away
     * @return created row
     */
    public WebCredential create(String host, WebAuthType authType, String username,
                                String secret, String headerName, boolean enabled) {
        String normalizedHost = normalizeHost(host);
        requireShape(authType, username, secret, headerName, true);
        Long existing = webCredentialMapper.selectCount(new LambdaQueryWrapper<WebCredential>()
                .eq(WebCredential::getHost, normalizedHost));
        if (existing != null && existing > 0) {
            throw BizException.invalidParam("该 host 已配置凭据，请编辑或删除后重建");
        }
        WebCredential credential = new WebCredential();
        credential.setCredentialId(bizIdGenerator.webCredentialId());
        credential.setHost(normalizedHost);
        credential.setAuthType(authType);
        credential.setUsername(authType == WebAuthType.BASIC ? username : null);
        credential.setSecret(secret);
        credential.setHeaderName(authType == WebAuthType.HEADER ? headerName : null);
        credential.setEnabled(enabled ? ENABLED : DISABLED);
        webCredentialMapper.insert(credential);
        // The secret deliberately never appears in a log line.
        log.info("web credential created, credentialId={}, host={}, authType={}, enabled={}",
                credential.getCredentialId(), normalizedHost, authType, enabled);
        return credential;
    }

    /**
     * Updates a credential. Every field is optional; a {@code null} (or blank secret) keeps the
     * stored value, so the console can flip {@code enabled} without holding the password.
     *
     * @param credentialId business id
     * @param username     new BASIC username, {@code null} keeps
     * @param secret       new secret, {@code null} or blank keeps the stored one
     * @param headerName   new HEADER header name, {@code null} keeps
     * @param enabled      new switch value, {@code null} keeps
     * @return updated row
     */
    public WebCredential update(String credentialId, String username, String secret,
                                String headerName, Boolean enabled) {
        WebCredential credential = require(credentialId);
        if (username != null) {
            credential.setUsername(username);
        }
        if (secret != null && !secret.isBlank()) {
            credential.setSecret(secret);
        }
        if (headerName != null) {
            credential.setHeaderName(headerName);
        }
        if (enabled != null) {
            credential.setEnabled(enabled ? ENABLED : DISABLED);
        }
        requireShape(credential.getAuthType(), credential.getUsername(), credential.getSecret(),
                credential.getHeaderName(), false);
        webCredentialMapper.updateById(credential);
        log.info("web credential updated, credentialId={}, host={}, enabled={}",
                credentialId, credential.getHost(), credential.getEnabled());
        return credential;
    }

    /**
     * Removes a credential for good. Hard delete: a deleted secret should be gone, and a soft
     * flagged row would hold {@code uk_tenant_host} hostage.
     *
     * @param credentialId business id
     */
    public void remove(String credentialId) {
        WebCredential credential = require(credentialId);
        webCredentialMapper.hardDeleteById(credential.getId());
        log.info("web credential removed, credentialId={}, host={}", credentialId, credential.getHost());
    }

    /**
     * Resolves the injectable credential one tenant holds for a host, {@code null} when it has none
     * enabled there.
     *
     * <p>The tenant is a parameter rather than something read from the context on purpose: the only
     * caller is the sync pass, and on the scheduled thread there is no context to read. It is also
     * the entire isolation of the fetch side - the row level fence covers console threads and skips
     * this one - so a missing tenant must mean "no credential", never "any credential of this
     * host". That is the one defensive check of this chain, and it is here rather than in the
     * caller because here is where forgetting it costs a password.
     *
     * @param tenantId tenant of the knowledge base being synced
     * @param host     host of the URL about to be fetched
     * @return resolved credential or {@code null}
     */
    public FetchCredential resolveFor(String tenantId, String host) {
        if (tenantId == null || tenantId.isBlank() || host == null || host.isBlank()) {
            return null;
        }
        WebCredential credential = webCredentialMapper.selectOne(new LambdaQueryWrapper<WebCredential>()
                .eq(WebCredential::getTenantId, tenantId)
                .eq(WebCredential::getHost, host.toLowerCase(Locale.ROOT))
                .eq(WebCredential::getEnabled, ENABLED));
        return credential == null ? null : FetchCredential.of(credential);
    }

    private WebCredential require(String credentialId) {
        WebCredential credential = webCredentialMapper.selectOne(new LambdaQueryWrapper<WebCredential>()
                .eq(WebCredential::getCredentialId, credentialId));
        if (credential == null) {
            throw BizException.notFound("站点凭据不存在：" + credentialId);
        }
        return credential;
    }

    /**
     * The one shape gate of both write paths: BASIC needs username plus secret, HEADER needs header
     * name plus value. On update the secret already sits on the row, so only creation insists on it
     * arriving with the request.
     */
    private void requireShape(WebAuthType authType, String username, String secret,
                              String headerName, boolean creating) {
        if (authType == null) {
            throw BizException.invalidParam("认证类型不能为空");
        }
        if (creating && (secret == null || secret.isBlank())) {
            throw BizException.invalidParam("凭据内容不能为空");
        }
        if (authType == WebAuthType.BASIC && (username == null || username.isBlank())) {
            throw BizException.invalidParam("BASIC 凭据必须提供用户名");
        }
        if (authType == WebAuthType.HEADER && (headerName == null || headerName.isBlank())) {
            throw BizException.invalidParam("HEADER 凭据必须提供请求头名称");
        }
    }

    private String normalizeHost(String host) {
        if (host == null || host.isBlank()) {
            throw BizException.invalidParam("host 不能为空");
        }
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("/") || normalized.contains("@") || normalized.contains(" ")) {
            throw BizException.invalidParam("host 只能是主机名（可带端口），不能包含路径或协议");
        }
        return normalized;
    }
}
