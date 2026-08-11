package io.kbrag.app.memory;

import io.kbrag.app.openapi.ApiRateLimiter;
import io.kbrag.app.support.MybatisLambdaCache;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.MemoryAppKey;
import io.kbrag.domain.enums.MemoryAppKeyStatus;
import io.kbrag.domain.mapper.MemoryAppKeyMapper;
import io.kbrag.domain.service.BizIdGenerator;
import io.kbrag.domain.service.MemoryKeyFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the memory key trust boundary: authentication tells "mistyped" apart from "withdrawn",
 * the principal carries the one bound library, and rotation swaps the digest while dropping the
 * stale rate limit bucket.
 *
 * @author owlzhangfq@gmail.com
 */
class MemoryAppKeyServiceTest {

    private static final String LIBRARY_ID = "mlib_1";
    private static final String KEY_ID = "mkey_1";
    private static final String PLAINTEXT = "kb-mk-0123456789abcdef0123456789abcdef0123456789abcdef";

    private MemoryAppKeyMapper memoryAppKeyMapper;
    private MemoryKeyFactory memoryKeyFactory;
    private ApiRateLimiter apiRateLimiter;
    private MemoryAppKeyService service;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.register(MemoryAppKey.class);
        memoryAppKeyMapper = mock(MemoryAppKeyMapper.class);
        memoryKeyFactory = mock(MemoryKeyFactory.class);
        apiRateLimiter = mock(ApiRateLimiter.class);
        KbProperties properties = new KbProperties();
        service = new MemoryAppKeyService(memoryAppKeyMapper, memoryKeyFactory,
                mock(BizIdGenerator.class), apiRateLimiter, properties, Runnable::run);
    }

    @Test
    void shouldRejectAMalformedCredentialAsInvalid() {
        when(memoryKeyFactory.looksLikeKey("not-a-key")).thenReturn(false);

        BizException e = assertThrows(BizException.class, () -> service.authenticate("not-a-key"));
        assertEquals(ErrorCode.INVALID_API_KEY, e.getErrorCode());
    }

    @Test
    void shouldRejectAnUnknownCredentialAsInvalid() {
        when(memoryKeyFactory.looksLikeKey(PLAINTEXT)).thenReturn(true);
        when(memoryKeyFactory.hash(PLAINTEXT)).thenReturn("digest");
        when(memoryAppKeyMapper.selectOne(any())).thenReturn(null);

        BizException e = assertThrows(BizException.class, () -> service.authenticate(PLAINTEXT));
        assertEquals(ErrorCode.INVALID_API_KEY, e.getErrorCode());
    }

    @Test
    void shouldTellAWithdrawnKeyApartFromAMistypedOne() {
        MemoryAppKey key = keyRow();
        key.setStatus(MemoryAppKeyStatus.DISABLED);
        when(memoryKeyFactory.looksLikeKey(PLAINTEXT)).thenReturn(true);
        when(memoryKeyFactory.hash(PLAINTEXT)).thenReturn("digest");
        when(memoryAppKeyMapper.selectOne(any())).thenReturn(key);

        BizException e = assertThrows(BizException.class, () -> service.authenticate(PLAINTEXT));
        assertEquals(ErrorCode.API_KEY_DISABLED, e.getErrorCode());
    }

    @Test
    void shouldHandTheBoundLibraryToThePrincipal() {
        when(memoryKeyFactory.looksLikeKey(PLAINTEXT)).thenReturn(true);
        when(memoryKeyFactory.hash(PLAINTEXT)).thenReturn("digest");
        when(memoryAppKeyMapper.selectOne(any())).thenReturn(keyRow());

        MemoryKeyPrincipal principal = service.authenticate(PLAINTEXT);

        assertEquals(KEY_ID, principal.getKeyId());
        assertEquals(LIBRARY_ID, principal.getLibraryId());
        assertEquals(25, principal.getQpsLimit());
    }

    @Test
    void shouldSwapTheDigestAndDropTheBucketOnRotation() {
        MemoryAppKey key = keyRow();
        when(memoryAppKeyMapper.selectOne(any())).thenReturn(key);
        when(memoryKeyFactory.generate()).thenReturn(
                new MemoryKeyFactory.GeneratedKey("kb-mk-new", "newdigest", "kb-mk-ne…w123"));

        MemoryAppKeyService.IssuedKey rotated = service.rotate(LIBRARY_ID, KEY_ID);

        assertEquals("kb-mk-new", rotated.plaintext());
        assertEquals("newdigest", key.getKeyHash());
        verify(memoryAppKeyMapper).updateById(key);
        verify(apiRateLimiter).forget(KEY_ID);
    }

    @Test
    void shouldAnswerNotFoundForAKeyOfAnotherLibrary() {
        when(memoryAppKeyMapper.selectOne(any())).thenReturn(null);

        assertThrows(BizException.class, () -> service.updateStatus(LIBRARY_ID, KEY_ID,
                MemoryAppKeyStatus.DISABLED));
        verify(apiRateLimiter, never()).forget(anyString());
    }

    private MemoryAppKey keyRow() {
        MemoryAppKey key = new MemoryAppKey();
        key.setId(3L);
        key.setKeyId(KEY_ID);
        key.setLibraryId(LIBRARY_ID);
        key.setName("agent");
        key.setKeyHash("digest");
        key.setKeyPrefix("kb-mk-01…cdef");
        key.setStatus(MemoryAppKeyStatus.ENABLED);
        key.setQpsLimit(25);
        return key;
    }
}
