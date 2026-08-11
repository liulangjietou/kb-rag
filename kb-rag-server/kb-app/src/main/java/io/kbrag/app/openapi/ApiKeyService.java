package io.kbrag.app.openapi;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import io.kbrag.app.appcenter.AppService;
import io.kbrag.app.config.AsyncConfig;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.ApiKey;
import io.kbrag.domain.enums.ApiKeyStatus;
import io.kbrag.domain.mapper.ApiKeyMapper;
import io.kbrag.domain.service.ApiKeyFactory;
import io.kbrag.domain.service.BizIdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * API key management and authentication, requirement section 4.8.
 *
 * <p><b>The plaintext exists once.</b> {@link #create} and {@link #rotate} are the only methods that ever
 * hold it, they return it to the console exactly once, and nothing persists it. Every later operation works
 * on the digest, which is why a rotated key invalidates the old one immediately and irreversibly: the old
 * digest is overwritten, so no lookup can match it again.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
public class ApiKeyService {

    private final ApiKeyMapper apiKeyMapper;
    private final ApiKeyFactory apiKeyFactory;
    private final AppService appService;
    private final BizIdGenerator bizIdGenerator;
    private final KbProperties properties;
    private final Executor auditExecutor;

    public ApiKeyService(ApiKeyMapper apiKeyMapper,
                         ApiKeyFactory apiKeyFactory,
                         AppService appService,
                         BizIdGenerator bizIdGenerator,
                         KbProperties properties,
                         @Qualifier(AsyncConfig.AUDIT_EXECUTOR) Executor auditExecutor) {
        this.apiKeyMapper = apiKeyMapper;
        this.apiKeyFactory = apiKeyFactory;
        this.appService = appService;
        this.bizIdGenerator = bizIdGenerator;
        this.properties = properties;
        this.auditExecutor = auditExecutor;
    }

    /**
     * Mints a key.
     *
     * @param name     display name of the caller it is issued to
     * @param qpsLimit token bucket rate, {@code null} takes the deployment default
     * @param appScope allowed application ids, empty or {@code null} authorises every application
     * @return stored row plus the plaintext, which the caller must show once and then forget
     */
    @Transactional(rollbackFor = Exception.class)
    public IssuedKey create(String name, Integer qpsLimit, List<String> appScope) {
        requireScopeExists(appScope);
        ApiKeyFactory.GeneratedKey generated = apiKeyFactory.generate();
        ApiKey key = new ApiKey();
        key.setKeyId(bizIdGenerator.apiKeyId());
        key.setName(name);
        key.setKeyHash(generated.hash());
        key.setPrefix(generated.prefix());
        key.setStatus(ApiKeyStatus.ENABLED);
        key.setQpsLimit(qpsLimit == null || qpsLimit <= 0
                ? properties.getOpenApi().getRateLimitDefault() : qpsLimit);
        key.setAppScope(CollectionUtils.isEmpty(appScope) ? null : JsonUtil.toJson(appScope));
        apiKeyMapper.insert(key);
        log.info("api key created, keyId={}, name={}, qpsLimit={}, scoped={}",
                key.getKeyId(), name, key.getQpsLimit(), !CollectionUtils.isEmpty(appScope));
        return new IssuedKey(key, generated.plaintext());
    }

    /**
     * Lists every key, newest first. The rows carry the digest, so callers must never serialise them directly.
     *
     * @return keys
     */
    public List<ApiKey> list() {
        return apiKeyMapper.selectList(new LambdaQueryWrapper<ApiKey>().orderByDesc(ApiKey::getId));
    }

    /**
     * Loads a key or fails.
     *
     * @param keyId key business id
     * @return key row
     */
    public ApiKey require(String keyId) {
        ApiKey key = apiKeyMapper.selectOne(new LambdaQueryWrapper<ApiKey>()
                .eq(ApiKey::getKeyId, keyId)
                .last("limit 1"));
        if (key == null) {
            throw BizException.notFound("api key not found");
        }
        return key;
    }

    /**
     * Enables or disables a key.
     *
     * @param keyId  key business id
     * @param status new status
     * @return updated row
     */
    @Transactional(rollbackFor = Exception.class)
    public ApiKey updateStatus(String keyId, ApiKeyStatus status) {
        ApiKey key = require(keyId);
        key.setStatus(status);
        apiKeyMapper.updateById(key);
        log.info("api key status updated, keyId={}, status={}", keyId, status);
        return key;
    }

    /**
     * Replaces the authorisation scope of a key.
     *
     * @param keyId    key business id
     * @param appScope allowed application ids, empty or {@code null} authorises every application
     * @return updated row
     */
    @Transactional(rollbackFor = Exception.class)
    public ApiKey updateScope(String keyId, List<String> appScope) {
        requireScopeExists(appScope);
        ApiKey key = require(keyId);
        key.setAppScope(CollectionUtils.isEmpty(appScope) ? null : JsonUtil.toJson(appScope));
        apiKeyMapper.updateById(key);
        log.info("api key scope updated, keyId={}, scoped={}", keyId, !CollectionUtils.isEmpty(appScope));
        return key;
    }

    /**
     * Replaces the secret of a key, keeping its id, name, quota and scope.
     *
     * <p>The old secret stops working the moment this commits, with no grace window. A rotation exists because
     * a secret is suspected of having leaked; a window during which the leaked value still works would defeat
     * the purpose of the operation.
     *
     * @param keyId key business id
     * @return stored row plus the new plaintext
     */
    @Transactional(rollbackFor = Exception.class)
    public IssuedKey rotate(String keyId) {
        ApiKey key = require(keyId);
        ApiKeyFactory.GeneratedKey generated = apiKeyFactory.generate();
        key.setKeyHash(generated.hash());
        key.setPrefix(generated.prefix());
        apiKeyMapper.updateById(key);
        log.info("api key rotated, keyId={}", keyId);
        return new IssuedKey(key, generated.plaintext());
    }

    /**
     * Soft deletes a key.
     *
     * @param keyId key business id
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(String keyId) {
        ApiKey key = require(keyId);
        apiKeyMapper.deleteById(key.getId());
        log.info("api key deleted, keyId={}", keyId);
    }

    /**
     * Authenticates a presented key.
     *
     * <p>Two distinct rejections, on purpose. A string that is not a key of this system, or whose digest
     * matches no row, is {@code INVALID_API_KEY}; a key that exists but was revoked is
     * {@code API_KEY_DISABLED}, so the caller learns its credential was withdrawn rather than mistyped. The
     * distinction leaks nothing an attacker can use: guessing a 48 hexadecimal character secret is the hard
     * part, and it is unaffected by knowing whether a guess named a disabled key.
     *
     * @param plaintext key as the {@code Authorization} header presented it
     * @return authenticated principal
     */
    public ApiKeyPrincipal authenticate(String plaintext) {
        if (!apiKeyFactory.looksLikeKey(plaintext)) {
            throw new BizException(ErrorCode.INVALID_API_KEY, "API Key 格式不正确");
        }
        ApiKey key = apiKeyMapper.selectOne(new LambdaQueryWrapper<ApiKey>()
                .eq(ApiKey::getKeyHash, apiKeyFactory.hash(plaintext))
                .last("limit 1"));
        if (key == null) {
            throw new BizException(ErrorCode.INVALID_API_KEY, "API Key 无效");
        }
        if (key.getStatus() == ApiKeyStatus.DISABLED) {
            throw new BizException(ErrorCode.API_KEY_DISABLED, "API Key 已被禁用");
        }
        touchAsync(key.getKeyId());
        return new ApiKeyPrincipal(key.getKeyId(), key.getName(),
                key.getQpsLimit() == null ? properties.getOpenApi().getRateLimitDefault() : key.getQpsLimit(),
                scopeOf(key));
    }

    /**
     * Records the last use of a key off the request path.
     *
     * <p>Asynchronous and best effort: a write on every call would put a row update in front of every search,
     * and the value it maintains is an operational hint, not an audit fact - the audit table is where the
     * facts live.
     *
     * <p>Handed to the executor directly rather than through {@code @Async}, because its only caller is
     * {@link #authenticate} on this same bean: a proxied annotation is bypassed there, and the update would
     * run inline on the very request path it exists to stay off - once per authenticated open API call.
     *
     * @param keyId key business id
     */
    private void touchAsync(String keyId) {
        auditExecutor.execute(() -> {
            try {
                apiKeyMapper.update(null, new LambdaUpdateWrapper<ApiKey>()
                        .set(ApiKey::getLastUsedAt, LocalDateTime.now())
                        .eq(ApiKey::getKeyId, keyId));
            } catch (Exception e) {
                log.error("api key last used timestamp not updated, errorCode={}, keyId={}",
                        ErrorCode.INTERNAL_ERROR, keyId, e);
            }
        });
    }

    /**
     * Parses the authorisation scope of a stored key.
     *
     * @param key stored row
     * @return allowed application ids, empty when unrestricted
     */
    public List<String> scopeOf(ApiKey key) {
        if (key.getAppScope() == null || key.getAppScope().isBlank()) {
            return List.of();
        }
        List<String> scope = JsonUtil.parse(key.getAppScope(), new TypeReference<List<String>>() {
        });
        return scope == null ? List.of() : scope;
    }

    /**
     * Fast-fails a scope naming an application that does not exist.
     *
     * <p>A scope entry with a typo would silently authorise nothing, and the operator would only discover it
     * when the external caller reports a 403 they cannot explain.
     *
     * @param appScope requested scope
     */
    private void requireScopeExists(List<String> appScope) {
        if (CollectionUtils.isEmpty(appScope)) {
            return;
        }
        for (String appId : appScope) {
            appService.require(appId);
        }
    }

    /**
     * A freshly minted or rotated key: the stored row plus its plaintext.
     *
     * @param key       stored row
     * @param plaintext full key, to be shown exactly once and never stored
     */
    public record IssuedKey(ApiKey key, String plaintext) {
    }
}
