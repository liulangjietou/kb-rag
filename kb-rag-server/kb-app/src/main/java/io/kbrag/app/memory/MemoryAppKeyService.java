package io.kbrag.app.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.kbrag.app.config.AsyncConfig;
import io.kbrag.app.openapi.ApiRateLimiter;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.MemoryAppKey;
import io.kbrag.domain.enums.MemoryAppKeyStatus;
import io.kbrag.domain.mapper.MemoryAppKeyMapper;
import io.kbrag.domain.service.BizIdGenerator;
import io.kbrag.domain.service.MemoryKeyFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Memory key management and authentication, the M19 contract.
 *
 * <p>Same secret discipline as the knowledge API key service: the plaintext exists once, inside the
 * issue and rotate responses, and every later operation works on the SHA-256 digest. The one
 * structural difference is the library binding - a memory key authorises exactly one library, so
 * authentication resolves the library and hands it to the principal instead of carrying a scope
 * list.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
public class MemoryAppKeyService {

    private final MemoryAppKeyMapper memoryAppKeyMapper;
    private final MemoryKeyFactory memoryKeyFactory;
    private final BizIdGenerator bizIdGenerator;
    private final ApiRateLimiter apiRateLimiter;
    private final KbProperties properties;
    private final Executor auditExecutor;

    public MemoryAppKeyService(MemoryAppKeyMapper memoryAppKeyMapper,
                               MemoryKeyFactory memoryKeyFactory,
                               BizIdGenerator bizIdGenerator,
                               ApiRateLimiter apiRateLimiter,
                               KbProperties properties,
                               @Qualifier(AsyncConfig.AUDIT_EXECUTOR) Executor auditExecutor) {
        this.memoryAppKeyMapper = memoryAppKeyMapper;
        this.memoryKeyFactory = memoryKeyFactory;
        this.bizIdGenerator = bizIdGenerator;
        this.apiRateLimiter = apiRateLimiter;
        this.properties = properties;
        this.auditExecutor = auditExecutor;
    }

    /**
     * Mints a key bound to one library.
     *
     * @param libraryId library the key will authorise, existence checked by the caller
     * @param name      purpose note, e.g. the consuming agent's name
     * @param qpsLimit  token bucket rate, {@code null} or non positive takes the deployment default
     * @return stored row plus the plaintext, shown once and never stored
     */
    @Transactional(rollbackFor = Exception.class)
    public IssuedKey issue(String libraryId, String name, Integer qpsLimit) {
        MemoryKeyFactory.GeneratedKey generated = memoryKeyFactory.generate();
        MemoryAppKey key = new MemoryAppKey();
        key.setKeyId(bizIdGenerator.memoryAppKeyId());
        key.setLibraryId(libraryId);
        key.setName(name);
        key.setKeyHash(generated.hash());
        key.setKeyPrefix(generated.prefix());
        key.setStatus(MemoryAppKeyStatus.ENABLED);
        key.setQpsLimit(qpsLimit == null || qpsLimit <= 0
                ? properties.getOpenApi().getRateLimitDefault() : qpsLimit);
        memoryAppKeyMapper.insert(key);
        log.info("memory key issued, keyId={}, libraryId={}, qpsLimit={}",
                key.getKeyId(), libraryId, key.getQpsLimit());
        return new IssuedKey(key, generated.plaintext());
    }

    /**
     * Lists the keys of one library, newest first.
     *
     * @param libraryId library business id
     * @return keys, digests included - callers must never serialise the rows directly
     */
    public List<MemoryAppKey> listByLibrary(String libraryId) {
        return memoryAppKeyMapper.selectList(new LambdaQueryWrapper<MemoryAppKey>()
                .eq(MemoryAppKey::getLibraryId, libraryId)
                .orderByDesc(MemoryAppKey::getId));
    }

    /**
     * Loads a key of one library or fails.
     *
     * @param libraryId library business id, the ownership predicate
     * @param keyId     key business id
     * @return key row
     */
    public MemoryAppKey require(String libraryId, String keyId) {
        MemoryAppKey key = memoryAppKeyMapper.selectOne(new LambdaQueryWrapper<MemoryAppKey>()
                .eq(MemoryAppKey::getLibraryId, libraryId)
                .eq(MemoryAppKey::getKeyId, keyId)
                .last("limit 1"));
        if (key == null) {
            throw BizException.notFound("memory key not found");
        }
        return key;
    }

    /**
     * Enables or disables a key. Disabling also drops its rate limit bucket so a re-enabled key
     * starts with full quota.
     *
     * @param libraryId library business id
     * @param keyId     key business id
     * @param status    new status
     * @return updated row
     */
    @Transactional(rollbackFor = Exception.class)
    public MemoryAppKey updateStatus(String libraryId, String keyId, MemoryAppKeyStatus status) {
        MemoryAppKey key = require(libraryId, keyId);
        key.setStatus(status);
        memoryAppKeyMapper.updateById(key);
        apiRateLimiter.forget(keyId);
        log.info("memory key status updated, keyId={}, status={}", keyId, status);
        return key;
    }

    /**
     * Replaces the secret of a key, keeping its id, binding, name and quota.
     *
     * <p>No grace window, same reasoning as the knowledge API key rotation: a rotation happens
     * because the secret is suspected leaked, and a window during which the leaked value still
     * works would defeat it.
     *
     * @param libraryId library business id
     * @param keyId     key business id
     * @return stored row plus the new plaintext
     */
    @Transactional(rollbackFor = Exception.class)
    public IssuedKey rotate(String libraryId, String keyId) {
        MemoryAppKey key = require(libraryId, keyId);
        MemoryKeyFactory.GeneratedKey generated = memoryKeyFactory.generate();
        key.setKeyHash(generated.hash());
        key.setKeyPrefix(generated.prefix());
        memoryAppKeyMapper.updateById(key);
        apiRateLimiter.forget(keyId);
        log.info("memory key rotated, keyId={}", keyId);
        return new IssuedKey(key, generated.plaintext());
    }

    /**
     * Soft deletes a key.
     *
     * @param libraryId library business id
     * @param keyId     key business id
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(String libraryId, String keyId) {
        MemoryAppKey key = require(libraryId, keyId);
        memoryAppKeyMapper.deleteById(key.getId());
        apiRateLimiter.forget(keyId);
        log.info("memory key deleted, keyId={}", keyId);
    }

    /**
     * Soft deletes every key of one library, the cleanup of a library deletion.
     *
     * @param libraryId library business id
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteByLibrary(String libraryId) {
        for (MemoryAppKey key : listByLibrary(libraryId)) {
            memoryAppKeyMapper.deleteById(key.getId());
            apiRateLimiter.forget(key.getKeyId());
        }
        log.info("memory keys deleted with library, libraryId={}", libraryId);
    }

    /**
     * Authenticates a presented key.
     *
     * <p>Two distinct rejections, same reasoning as the knowledge API key: an unknown or malformed
     * credential is {@code INVALID_API_KEY}, a known but revoked one is {@code API_KEY_DISABLED}.
     * The distinction tells a caller "withdrawn" apart from "mistyped" and gives an attacker
     * nothing, since guessing the 48 hexadecimal character secret is the hard part either way.
     *
     * @param plaintext key as the {@code Authorization} header presented it
     * @return authenticated principal carrying the bound library
     */
    public MemoryKeyPrincipal authenticate(String plaintext) {
        if (!memoryKeyFactory.looksLikeKey(plaintext)) {
            throw new BizException(ErrorCode.INVALID_API_KEY, "Memory Key 格式不正确");
        }
        MemoryAppKey key = memoryAppKeyMapper.selectOne(new LambdaQueryWrapper<MemoryAppKey>()
                .eq(MemoryAppKey::getKeyHash, memoryKeyFactory.hash(plaintext))
                .last("limit 1"));
        if (key == null) {
            throw new BizException(ErrorCode.INVALID_API_KEY, "Memory Key 无效");
        }
        if (key.getStatus() == MemoryAppKeyStatus.DISABLED) {
            throw new BizException(ErrorCode.API_KEY_DISABLED, "Memory Key 已被禁用");
        }
        touchAsync(key.getKeyId());
        return new MemoryKeyPrincipal(key.getKeyId(), key.getLibraryId(), key.getName(),
                key.getQpsLimit() == null
                        ? properties.getOpenApi().getRateLimitDefault() : key.getQpsLimit());
    }

    /**
     * Records the last use of a key off the request path, asynchronous and best effort - an
     * operational hint, not an audit fact.
     *
     * <p>Handed to the executor directly rather than through {@code @Async}: its only caller is
     * {@link #authenticate} on this same bean, where a proxied annotation is bypassed and the update
     * would run inline on the request path it exists to stay off.
     *
     * @param keyId key business id
     */
    private void touchAsync(String keyId) {
        auditExecutor.execute(() -> {
            try {
                memoryAppKeyMapper.update(null, new LambdaUpdateWrapper<MemoryAppKey>()
                        .set(MemoryAppKey::getLastUsedAt, LocalDateTime.now())
                        .eq(MemoryAppKey::getKeyId, keyId));
            } catch (Exception e) {
                log.error("memory key last used timestamp not updated, errorCode={}, keyId={}",
                        ErrorCode.INTERNAL_ERROR, keyId, e);
            }
        });
    }

    /**
     * A freshly minted or rotated key: the stored row plus its plaintext.
     *
     * @param key       stored row
     * @param plaintext full key, to be shown exactly once and never stored
     */
    public record IssuedKey(MemoryAppKey key, String plaintext) {
    }
}
