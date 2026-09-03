package io.kbrag.infrastructure.auth;

import cn.dev33.satoken.dao.SaTokenDao;
import io.kbrag.domain.mapper.AuthSessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 覆盖 MySQL 会话存储与 Sa-Token 存储契约的等价性。
 *
 * <p>这些用例针对的不是"能不能存取"，而是几条一旦偏离就会变成安全问题的边界：框架用
 * {@code timeout} 的取值表达"永久""不要存"两种意图，用 {@code update} 表达"仅在键还活着时改值"。
 * 把任何一条实现成常识里的样子——比如把 {@code update} 写成 upsert——都不会让功能看起来坏掉，
 * 却会让一次性票据变成永久会话、让已登出的会话被续期请求复活。
 *
 * @author owlzhangfq@gmail.com
 */
class MysqlSaTokenDaoTest {

    private static final String KEY = "satoken:login:token:abc";
    private static final String VALUE = "alice";

    private AuthSessionMapper authSessionMapper;
    private MysqlSaTokenDao dao;

    @BeforeEach
    void setUp() {
        authSessionMapper = mock(AuthSessionMapper.class);
        dao = new MysqlSaTokenDao(authSessionMapper);
    }

    @Test
    void shouldNotStoreWhenTimeoutSaysNotToStore() {
        // 0 和 <= -2 都是框架约定的"不要写"。少判一个分支，一次性票据就会被写成会话。
        dao.set(KEY, VALUE, 0L);
        dao.set(KEY, VALUE, SaTokenDao.NOT_VALUE_EXPIRE);
        dao.set(KEY, VALUE, -99L);

        verify(authSessionMapper, never()).upsert(anyString(), anyString(), anyLong());
    }

    @Test
    void shouldStoreForeverWhenTimeoutIsNeverExpire() {
        dao.set(KEY, VALUE, SaTokenDao.NEVER_EXPIRE);

        // 负数一路传到 SQL，由 SQL 把 expires_at 置空；应用层不自己换算成某个"很远的时刻"。
        verify(authSessionMapper).upsert(KEY, VALUE, SaTokenDao.NEVER_EXPIRE);
    }

    @Test
    void shouldStoreWithTimeout() {
        dao.set(KEY, VALUE, 3600L);

        verify(authSessionMapper).upsert(KEY, VALUE, 3600L);
    }

    @Test
    void shouldUpdateWithoutEverInserting() {
        dao.update(KEY, VALUE);

        // 键不存在时必须什么都不做。写成 upsert 会让一个已登出的会话被续期请求复活。
        verify(authSessionMapper).updateValueKeepTtl(KEY, VALUE);
        verify(authSessionMapper, never()).upsert(anyString(), anyString(), anyLong());
    }

    @Test
    void shouldReportMissingKeyAsNotValueExpire() {
        when(authSessionMapper.selectTtlSeconds(KEY)).thenReturn(null);

        // 框架靠 -2 和 -1 区分"没有这个键"和"这个键永不过期"。返回 0 或抛异常都会被误读。
        assertEquals(SaTokenDao.NOT_VALUE_EXPIRE, dao.getTimeout(KEY));
    }

    @Test
    void shouldPassThroughNeverExpireAndRemainingTimeout() {
        when(authSessionMapper.selectTtlSeconds(KEY)).thenReturn((long) SaTokenDao.NEVER_EXPIRE);
        assertEquals(SaTokenDao.NEVER_EXPIRE, dao.getTimeout(KEY));

        when(authSessionMapper.selectTtlSeconds(KEY)).thenReturn(120L);
        assertEquals(120L, dao.getTimeout(KEY));
    }

    @Test
    void shouldReadAndDeleteByKey() {
        when(authSessionMapper.selectValue(KEY)).thenReturn(VALUE);
        assertEquals(VALUE, dao.get(KEY));

        dao.delete(KEY);
        verify(authSessionMapper).deleteByKey(KEY);
    }

    @Test
    void shouldEscapeLikeWildcardsThatOccurInKeys() {
        when(authSessionMapper.selectKeysLike(anyString())).thenReturn(List.of());

        // 登录名里的下划线很常见。不转义的话 a_b 会把 axb 的会话一起捞出来。
        dao.searchData("satoken:login:token:", "a_b", 0, -1, false);

        verify(authSessionMapper).selectKeysLike(eq("satoken:login:token:%a\\_b%"));
    }

    @Test
    void shouldPageSearchResultsThroughTheFrameworkHelper() {
        when(authSessionMapper.selectKeysLike(anyString()))
                .thenReturn(List.of("k1", "k2", "k3"));

        List<String> page = dao.searchData("satoken:login:token:", "", 1, 1, false);

        // 分页交给框架工具，保证和官方 Redis 实现在同样入参下给出同一个窗口。
        assertEquals(List.of("k2"), page);
    }

    @Test
    void shouldPurgeExpiredRows() {
        when(authSessionMapper.purgeExpired()).thenReturn(3);

        assertEquals(3, dao.purgeExpired());
    }

    @Test
    void shouldTreatBlankSearchTermsAsEmpty() {
        when(authSessionMapper.selectKeysLike(anyString())).thenReturn(List.of());

        dao.searchData("", null, 0, -1, false);

        verify(authSessionMapper).selectKeysLike(eq("%%"));
    }

    @Test
    void shouldNotTouchTheTableWhileDecidingNotToStore() {
        dao.set(KEY, VALUE, 0L);

        verifyNoInteractions(authSessionMapper);
    }

    @Test
    void shouldUpdateTimeoutIncludingTheSwitchToNeverExpire() {
        dao.updateTimeout(KEY, 60L);
        verify(authSessionMapper).updateTtl(KEY, 60L);

        // 改成永久只需一条 UPDATE：SQL 能直接把 expires_at 置空，不必像 Redis 那样先读回值再整体重写。
        dao.updateTimeout(KEY, SaTokenDao.NEVER_EXPIRE);
        verify(authSessionMapper).updateTtl(KEY, SaTokenDao.NEVER_EXPIRE);
        verify(authSessionMapper, never()).selectValue(anyString());
    }

    @Test
    void shouldFollowStringOperationsForObjectAccess() {
        // 对象方法由接口默认实现转发到字符串方法，这里确认转发链没有被意外改写：
        // 删除一个对象就是删除它的键，取它的存活时间就是取键的存活时间。
        dao.deleteObject(KEY);
        verify(authSessionMapper).deleteByKey(KEY);

        when(authSessionMapper.selectTtlSeconds(KEY)).thenReturn(30L);
        assertTrue(dao.getObjectTimeout(KEY) == 30L);
    }
}
