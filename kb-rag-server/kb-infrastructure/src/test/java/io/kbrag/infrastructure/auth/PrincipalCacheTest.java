package io.kbrag.infrastructure.auth;

import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.enums.UserSource;
import io.kbrag.domain.model.UserPrincipal;
import io.kbrag.domain.port.PrincipalCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖两种权限缓存实现，重点在共享实现那些"错了也不会报错"的行为。
 *
 * <p>权限缓存的失效不是性能问题而是安全问题：一份没被清掉的授权，意味着一个刚被降权的账号还在以旧身份
 * 通过鉴权。因此这里除了基本读写，专门盯住三件事：①{@code UserPrincipal} 能原样往返序列化——少一个
 * 权限码就是一次错误的拒绝，多一个就是一次错误的放行；②读写失败降级、失效失败上抛，三者的安全含义不同；
 * ③{@code evictAll} 用 SCAN 而不是 KEYS。
 *
 * @author owlzhangfq@gmail.com
 */
class PrincipalCacheTest {

    private static final String USERNAME = "alice";

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private RedisPrincipalCache redisCache;
    private PrincipalCache localCache;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        redisCache = new RedisPrincipalCache(redisTemplate);
        localCache = new LocalPrincipalCache();
    }

    @Test
    void localCacheShouldRememberEvictAndClear() {
        localCache.put(USERNAME, principal());
        assertEquals(principal(), localCache.get(USERNAME));

        localCache.evict(USERNAME);
        assertNull(localCache.get(USERNAME));

        localCache.put(USERNAME, principal());
        localCache.evictAll();
        assertNull(localCache.get(USERNAME));
    }

    @Test
    void shouldRoundTripEveryFieldThroughJson() {
        // 这份对象就是鉴权的判据本身。序列化掉一个权限码会变成一次错误的拒绝，掉一个 kbId 会让人看不见
        // 本该看见的知识库，而 kbScopeAll 掉了则会把"看得见全部"降成"什么都看不见"。逐字段比对。
        UserPrincipal original = principal();

        UserPrincipal restored = JsonUtil.parse(JsonUtil.toJson(original), UserPrincipal.class);

        assertEquals(original, restored);
        assertEquals(original.permissions(), restored.permissions());
        assertEquals(original.roleIds(), restored.roleIds());
        assertEquals(original.kbIds(), restored.kbIds());
        assertEquals(original.source(), restored.source());
        assertTrue(restored.hasPermission("kb:read"));
        assertTrue(restored.canAccessKb("kb_1"));
    }

    @Test
    void shouldRoundTripAPrincipalThatCarriesNothing() {
        // 授权为空的账号是正常状态（等管理员配角色），不是异常。JsonUtil 配了 NON_NULL，空集合必须仍能
        // 还原成空集合而不是 null，否则第一次鉴权就会空指针。
        UserPrincipal empty = new UserPrincipal("u2", "t1", "bob", "Bob", UserSource.LOCAL,
                Set.of(), Set.of(), Set.of(), false, Set.of());

        UserPrincipal restored = JsonUtil.parse(JsonUtil.toJson(empty), UserPrincipal.class);

        assertEquals(empty, restored);
        assertTrue(restored.permissions().isEmpty());
        assertTrue(restored.kbIds().isEmpty());
    }

    @Test
    void redisCacheShouldStoreWithATimeToLive() {
        redisCache.put(USERNAME, principal());

        // 带过期时间，因为共享存储没有"进程重启清空"这个兜底：被删账号的条目会永远留着。
        verify(valueOps).set(eq(RedisPrincipalCache.KEY_PREFIX + USERNAME), anyString(), any(Duration.class));
    }

    @Test
    void redisCacheShouldFallBackToAMissWhenTheReadFails() {
        when(valueOps.get(anyString())).thenThrow(new QueryTimeoutException("redis down"));

        // 读不到就当没缓存，调用方回落到数据库这个事实源，答案依然正确。让整个控制台在 Redis 抖动时
        // 不可用，比多查四次数据库糟得多。
        assertNull(redisCache.get(USERNAME));
    }

    @Test
    void redisCacheShouldSwallowAWriteFailure() {
        doThrow(new QueryTimeoutException("redis down"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));

        // 写不进去只是下次再查一遍，没有正确性后果，不该让它失败一次登录。
        redisCache.put(USERNAME, principal());
    }

    @Test
    void redisCacheMustNotSwallowAnEvictionFailure() {
        when(redisTemplate.delete(anyString())).thenThrow(new QueryTimeoutException("redis down"));

        // 与上面两条相反：失效失败意味着旧授权还在生效。吞掉异常，调用方就会以为权限已经收回，
        // 而那正是这份缓存唯一不能出错的地方。
        assertThrows(QueryTimeoutException.class, () -> redisCache.evict(USERNAME));
    }

    @Test
    void redisCacheMustNotSwallowAClearFailure() {
        when(redisTemplate.scan(any())).thenThrow(new QueryTimeoutException("redis down"));

        assertThrows(QueryTimeoutException.class, () -> redisCache.evictAll());
    }

    @Test
    void redisCacheShouldDeleteByTheExactKey() {
        redisCache.evict(USERNAME);

        verify(redisTemplate).delete(RedisPrincipalCache.KEY_PREFIX + USERNAME);
    }

    @SuppressWarnings("unchecked")
    @Test
    void redisCacheShouldClearByScanningRatherThanKeys() {
        org.springframework.data.redis.core.Cursor<String> cursor =
                mock(org.springframework.data.redis.core.Cursor.class);
        when(cursor.hasNext()).thenReturn(true, true, false);
        when(cursor.next()).thenReturn(RedisPrincipalCache.KEY_PREFIX + "alice",
                RedisPrincipalCache.KEY_PREFIX + "bob");
        when(redisTemplate.scan(any())).thenReturn(cursor);
        when(redisTemplate.delete(any(List.class))).thenReturn(2L);

        redisCache.evictAll();

        // KEYS 会阻塞整个实例，而这套缓存很可能和会话共用一个 Redis——一次角色编辑不该让所有人卡住。
        verify(redisTemplate).scan(any());
        verify(redisTemplate).delete(List.of(RedisPrincipalCache.KEY_PREFIX + "alice",
                RedisPrincipalCache.KEY_PREFIX + "bob"));
    }

    private UserPrincipal principal() {
        return new UserPrincipal("u1", "t1", USERNAME, "Alice", UserSource.LDAP,
                Set.of("KB_ADMIN"), Set.of("role_1"), Set.of("kb:read", "kb:write"), false,
                Set.of("kb_1", "kb_2"));
    }
}
