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
import org.junit.jupiter.api.function.Executable;

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
 * the principal carries the one bound library, rotation swaps the digest while dropping the stale
 * rate limit bucket, and the console entries refuse a library outside the caller's tenant while
 * authentication stays free of that lookup.
 *
 * @author owlzhangfq@gmail.com
 */
class MemoryAppKeyServiceTest {

    private static final String LIBRARY_ID = "mlib_1";
    private static final String KEY_ID = "mkey_1";
    private static final String PLAINTEXT = "kb-mk-0123456789abcdef0123456789abcdef0123456789abcdef";

    private MemoryLibraryGuard memoryLibraryGuard;
    private MemoryAppKeyMapper memoryAppKeyMapper;
    private MemoryKeyFactory memoryKeyFactory;
    private ApiRateLimiter apiRateLimiter;
    private MemoryAppKeyService service;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.register(MemoryAppKey.class);
        memoryLibraryGuard = mock(MemoryLibraryGuard.class);
        memoryAppKeyMapper = mock(MemoryAppKeyMapper.class);
        memoryKeyFactory = mock(MemoryKeyFactory.class);
        apiRateLimiter = mock(ApiRateLimiter.class);
        KbProperties properties = new KbProperties();
        service = new MemoryAppKeyService(memoryLibraryGuard, memoryAppKeyMapper, memoryKeyFactory,
                mock(BizIdGenerator.class), apiRateLimiter, properties);
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

    @Test
    void shouldRefuseEveryConsoleEntryOfALibraryOutsideTheTenant() {
        // The key table carries no tenant_id, so a statement on (library_id, key_id) alone sits
        // outside the fence: without the library lookup a foreign tenant could disable, rotate or
        // delete a key by knowing the two ids from an audit line or a screenshot.
        when(memoryLibraryGuard.requireLibrary(LIBRARY_ID))
                .thenThrow(BizException.notFound("记忆库不存在"));

        // 404 rather than 403, same reasoning as the library entries: a 403 would confirm the
        // library exists to a tenant that may not see it.
        assertNotFound(() -> service.issue(LIBRARY_ID, "agent", 10));
        assertNotFound(() -> service.listByLibrary(LIBRARY_ID));
        assertNotFound(() -> service.updateStatus(LIBRARY_ID, KEY_ID, MemoryAppKeyStatus.DISABLED));
        assertNotFound(() -> service.rotate(LIBRARY_ID, KEY_ID));
        assertNotFound(() -> service.delete(LIBRARY_ID, KEY_ID));
        assertNotFound(() -> service.deleteByLibrary(LIBRARY_ID));

        verify(memoryAppKeyMapper, never()).selectOne(any());
        verify(memoryAppKeyMapper, never()).insert(any(MemoryAppKey.class));
        verify(memoryAppKeyMapper, never()).updateById(any(MemoryAppKey.class));
        verify(memoryAppKeyMapper, never()).deleteById(any(Long.class));
        verify(apiRateLimiter, never()).forget(anyString());
    }

    @Test
    void shouldAuthenticateWithoutResolvingTheLibrary() {
        // The open API thread has no console caller, so the fence is skipped there anyway; the
        // isolation of that surface is the key's own binding to one library. Adding a library
        // lookup here would cost a query per call and protect nothing.
        when(memoryKeyFactory.looksLikeKey(PLAINTEXT)).thenReturn(true);
        when(memoryKeyFactory.hash(PLAINTEXT)).thenReturn("digest");
        when(memoryAppKeyMapper.selectOne(any())).thenReturn(keyRow());

        service.authenticate(PLAINTEXT);

        verify(memoryLibraryGuard, never()).requireLibrary(anyString());
    }

    private void assertNotFound(Executable call) {
        BizException e = assertThrows(BizException.class, call);
        assertEquals(ErrorCode.NOT_FOUND, e.getErrorCode());
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
