package io.kbrag.app.auth;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import io.kbrag.common.util.HashUtil;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.AuthToken;
import io.kbrag.domain.mapper.AuthTokenMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the durability contract of the token store: only the digest reaches the database, a fresh
 * process resolves a previously issued token from the table, and expiry still evicts.
 *
 * @author owlzhangfq@gmail.com
 */
class TokenStoreTest {

    private static final String USERNAME = "admin";

    private AuthTokenMapper authTokenMapper;
    private TokenStore tokenStore;

    @BeforeEach
    void setUp() {
        authTokenMapper = mock(AuthTokenMapper.class);
        tokenStore = new TokenStore(authTokenMapper, new KbProperties());
    }

    @Test
    void shouldStoreOnlyTheDigestOfAnIssuedToken() {
        String token = tokenStore.issue(USERNAME);

        ArgumentCaptor<AuthToken> captor = ArgumentCaptor.forClass(AuthToken.class);
        verify(authTokenMapper).insert(captor.capture());
        AuthToken persisted = captor.getValue();
        assertEquals(HashUtil.sha256Hex(token), persisted.getTokenHash());
        assertNotEquals(token, persisted.getTokenHash());
        assertEquals(USERNAME, persisted.getUsername());
        assertTrue(persisted.getExpiresAt().isAfter(LocalDateTime.now()));
    }

    @Test
    void shouldResolveAnIssuedTokenFromTheCacheWithoutTouchingTheTable() {
        String token = tokenStore.issue(USERNAME);

        assertEquals(Optional.of(USERNAME), tokenStore.resolve(token));
        verify(authTokenMapper, atLeastOnce()).insert(any(AuthToken.class));
    }

    @Test
    void shouldResolveFromTheTableAfterARestart() {
        String token = tokenStore.issue(USERNAME);
        AuthToken row = new AuthToken();
        row.setTokenHash(HashUtil.sha256Hex(token));
        row.setUsername(USERNAME);
        row.setExpiresAt(LocalDateTime.now().plusHours(1));
        // 新建一个 TokenStore 实例模拟进程重启：内存缓存是空的，只能靠数据库找回会话
        AuthTokenMapper freshMapper = mock(AuthTokenMapper.class);
        when(freshMapper.selectOne(any())).thenReturn(row);
        TokenStore restarted = new TokenStore(freshMapper, new KbProperties());

        assertEquals(Optional.of(USERNAME), restarted.resolve(token));
    }

    @Test
    void shouldRejectAndEvictAnExpiredTokenFoundInTheTable() {
        AuthToken row = new AuthToken();
        row.setTokenHash(HashUtil.sha256Hex("stale-token"));
        row.setUsername(USERNAME);
        row.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(authTokenMapper.selectOne(any())).thenReturn(row);

        assertEquals(Optional.empty(), tokenStore.resolve("stale-token"));
        verify(authTokenMapper).delete(any(Wrapper.class));
    }

    @Test
    void shouldRejectAnUnknownToken() {
        assertEquals(Optional.empty(), tokenStore.resolve("never-issued"));
        assertEquals(Optional.empty(), tokenStore.resolve(null));
        assertEquals(Optional.empty(), tokenStore.resolve(" "));
    }

    @Test
    void shouldRevokeASingleTokenInCacheAndTable() {
        String token = tokenStore.issue(USERNAME);

        tokenStore.revoke(token);

        assertEquals(Optional.empty(), tokenStore.resolve(token));
        verify(authTokenMapper, atLeastOnce()).delete(any(Wrapper.class));
    }

    @Test
    void shouldRevokeEverySessionOfAUser() {
        String first = tokenStore.issue(USERNAME);
        String second = tokenStore.issue(USERNAME);

        tokenStore.revokeAll(USERNAME);

        assertEquals(Optional.empty(), tokenStore.resolve(first));
        assertEquals(Optional.empty(), tokenStore.resolve(second));
        verify(authTokenMapper, atLeastOnce()).delete(any(Wrapper.class));
    }
}
