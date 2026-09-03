package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.AuthSession;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * t_kb_auth_session 的数据访问，实现 Sa-Token 的 KV 契约。
 *
 * <p><b>过期一律在 SQL 里判定，而不是取回行以后在 Java 里比时间。</b> Redis 的过期是存储自己的行为，
 * 换成 MySQL 之后如果由应用判断，多实例部署下每个节点都拿自己的时钟去裁决同一行的生死，时钟漂移就会
 * 变成"A 节点认为会话还在、B 节点认为已过期"。所有语句统一用数据库的 {@code NOW()}，让过期只有一个
 * 裁决者。同理，写入时的绝对过期时刻也由 {@code DATE_ADD(NOW(), ...)} 算，而不是应用传时间戳。
 *
 * <p>已过期但尚未清理的行在每条语句里都被排除在外，因此 {@link #purgeExpired()} 只是回收空间，
 * 不承担正确性——延迟清理不会让任何一个过期会话被读成有效。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface AuthSessionMapper extends BaseMapper<AuthSession> {

    /**
     * 读取一个键的值，已过期的行视为不存在。
     *
     * @param key 存储键
     * @return 值，键不存在或已过期时为 {@code null}
     */
    @Select("""
            SELECT session_value FROM t_kb_auth_session
            WHERE session_key = #{key}
              AND (expires_at IS NULL OR expires_at > NOW())
            """)
    String selectValue(@Param("key") String key);

    /**
     * 写入一个键值对，已存在则连值带过期时刻一起覆盖。
     *
     * <p>用一条 {@code INSERT ... ON DUPLICATE KEY UPDATE} 而不是"先查再插或改"：后者在两个请求同时
     * 续期同一个会话时，会各自持有唯一键上的共享锁再去申请排他锁，形成锁升级死锁——本仓库在模型配额
     * 预占上刚踩过同一个坑。单条语句由 InnoDB 内部完成判定，不存在跨语句的锁升级窗口。
     *
     * <p>更新子句用 {@code VALUES()} 而非 8.0.19 才引入的行别名：项目只要求 MySQL 8，别名语法会让
     * 8.0.19 以下的部署直接语法报错，而 {@code VALUES()} 在整个 8.0 系列都可用（8.0.20 起仅告警）。
     *
     * @param key        存储键
     * @param value      存储值
     * @param ttlSeconds 存活秒数，负数表示永不过期
     * @return 受影响行数
     */
    @Insert("""
            INSERT INTO t_kb_auth_session (session_key, session_value, expires_at)
            VALUES (#{key}, #{value},
                    CASE WHEN #{ttlSeconds} < 0 THEN NULL
                         ELSE DATE_ADD(NOW(), INTERVAL #{ttlSeconds} SECOND) END)
            ON DUPLICATE KEY UPDATE
                session_value = VALUES(session_value),
                expires_at = VALUES(expires_at)
            """)
    int upsert(@Param("key") String key, @Param("value") String value,
               @Param("ttlSeconds") long ttlSeconds);

    /**
     * 仅当键仍然有效时覆写它的值，过期时刻保持不变。
     *
     * <p>对应 Sa-Token 的 {@code update}，语义等价于 Redis 的 {@code SET key value XX KEEPTTL}：
     * 键不存在时什么都不做，绝不能顺手插入一行——那会让一个已经登出的会话被续期请求复活。
     *
     * @param key   存储键
     * @param value 新值
     * @return 受影响行数，0 表示键不存在或已过期
     */
    @Update("""
            UPDATE t_kb_auth_session SET session_value = #{value}
            WHERE session_key = #{key}
              AND (expires_at IS NULL OR expires_at > NOW())
            """)
    int updateValueKeepTtl(@Param("key") String key, @Param("value") String value);

    /**
     * 读取一个键的剩余存活秒数。
     *
     * @param key 存储键
     * @return 永久为 {@code -1}，否则为剩余秒数；键不存在或已过期时为 {@code null}
     */
    @Select("""
            SELECT CASE WHEN expires_at IS NULL THEN -1
                        ELSE TIMESTAMPDIFF(SECOND, NOW(), expires_at) END
            FROM t_kb_auth_session
            WHERE session_key = #{key}
              AND (expires_at IS NULL OR expires_at > NOW())
            """)
    Long selectTtlSeconds(@Param("key") String key);

    /**
     * 改写一个仍然有效的键的过期时刻。
     *
     * @param key        存储键
     * @param ttlSeconds 新的存活秒数，负数表示永不过期
     * @return 受影响行数，0 表示键不存在或已过期
     */
    @Update("""
            UPDATE t_kb_auth_session
            SET expires_at = CASE WHEN #{ttlSeconds} < 0 THEN NULL
                                  ELSE DATE_ADD(NOW(), INTERVAL #{ttlSeconds} SECOND) END
            WHERE session_key = #{key}
              AND (expires_at IS NULL OR expires_at > NOW())
            """)
    int updateTtl(@Param("key") String key, @Param("ttlSeconds") long ttlSeconds);

    /**
     * 删除一个键。
     *
     * @param key 存储键
     * @return 受影响行数
     */
    @Delete("DELETE FROM t_kb_auth_session WHERE session_key = #{key}")
    int deleteByKey(@Param("key") String key);

    /**
     * 按 SQL {@code LIKE} 模式列出仍然有效的键，供 Sa-Token 的会话检索使用。
     *
     * <p>{@code ESCAPE} 显式声明成反斜杠，而不是依赖 MySQL 的默认值：调用方必须转义键里原本就存在的
     * {@code %} 和 {@code _}（登录名里的下划线是常见情况），而 {@code NO_BACKSLASH_ESCAPES} 这个
     * sql_mode 会让默认转义符失效，届时 {@code a_b} 会静默匹配到 {@code axb}。
     *
     * @param pattern LIKE 模式，调用方已把 Sa-Token 的 {@code *} 通配符翻译成 {@code %} 并转义了字面量
     * @return 命中的键，按键排序以保证分页稳定
     */
    @Select("""
            SELECT session_key FROM t_kb_auth_session
            WHERE session_key LIKE #{pattern} ESCAPE '\\\\'
              AND (expires_at IS NULL OR expires_at > NOW())
            ORDER BY session_key
            """)
    List<String> selectKeysLike(@Param("pattern") String pattern);

    /**
     * 回收已过期的行。
     *
     * <p>只影响占用空间：过期语义已经由每条语句里的 {@code NOW()} 比较保证，因此这个清理什么时候跑、
     * 跑没跑成功，都不会让过期会话被读成有效。
     *
     * @return 清理掉的行数
     */
    @Delete("DELETE FROM t_kb_auth_session WHERE expires_at IS NOT NULL AND expires_at <= NOW()")
    int purgeExpired();
}
