package io.kbrag.infrastructure.auth;

import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.model.UserPrincipal;
import io.kbrag.domain.port.PrincipalCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 共享的权限缓存，{@code kb.cache.provider=redis} 下使用，让多个节点看到同一份授权。
 *
 * <p><b>为什么是共享缓存，而不是本地缓存加失效广播。</b> 后者读起来更快，但广播是尽力而为的：某个节点
 * 正在重连时丢掉一条失效消息，它就会一直用着旧授权，而且外部完全看不出来。一个刚被降权的账号在那个
 * 节点上仍然是管理员，直到下一次全量失效碰巧发生。把缓存本身放在共享存储里，失效就是一次删除——要么
 * 删掉了，要么报错，不存在"以为删掉了"。多一次网络往返换掉一整类静默的授权不一致，值得。
 *
 * <p><b>读写失败降级，失效失败必须炸。</b> 三者的安全含义完全不同：读不到就当没缓存、回退到数据库这个
 * 事实源，答案仍然正确；写不进去只是下次再查一遍；而失效失败意味着旧授权还留在那里，如果这里把异常
 * 吞掉，调用方会以为权限已经收回。所以 {@link #evict} 与 {@link #evictAll} 让异常照常抛出，由调用方
 * 的事务决定回滚——宁可角色没改成，也不要改完了而旧授权还在生效。
 *
 * <p>条目带过期时间，这一点和进程内实现不同：那边靠进程重启兜底回收，共享存储没有这个性质，被删除的
 * 账号会把条目永远留在那里。过期只负责回收空间，不负责正确性——正确性由显式失效保证。
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@RequiredArgsConstructor
public class RedisPrincipalCache implements PrincipalCache {

    /** 键前缀，{@link #evictAll()} 按它扫描。 */
    static final String KEY_PREFIX = "kb:principal:";

    /**
     * 条目存活时间。
     *
     * <p>没有做成配置项：它只决定"已经没有读者的条目多久被回收"，不参与任何正确性判断，调它没有实际
     * 收益。取值与会话时长同量级即可——会话都过期的账号，它的权限缓存也没有再留着的理由。
     */
    private static final Duration TTL = Duration.ofHours(24);

    /** 一次 SCAN 取回和一次批量删除的规模，避免 evictAll 在大量条目上产生一条巨型命令。 */
    private static final int SCAN_BATCH = 500;

    private final StringRedisTemplate redisTemplate;

    @Override
    public UserPrincipal get(String username) {
        try {
            String json = redisTemplate.opsForValue().get(key(username));
            return json == null ? null : JsonUtil.parse(json, UserPrincipal.class);
        } catch (Exception e) {
            // 降级成未命中：调用方会回到数据库那个事实源，答案依然是对的，只是慢一点。
            // 让控制台在 Redis 抖动时整体不可用，比多查四次数据库糟得多。
            log.error("principal cache read failed, falling back to database, username={}, message={}",
                    username, e.getMessage(), e);
            return null;
        }
    }

    @Override
    public void put(String username, UserPrincipal principal) {
        try {
            redisTemplate.opsForValue().set(key(username), JsonUtil.toJson(principal), TTL);
        } catch (Exception e) {
            // 写不进去只是下次再查一遍，没有正确性后果，不该让它失败一次登录。
            log.error("principal cache write failed, username={}, message={}", username, e.getMessage(), e);
        }
    }

    @Override
    public void evict(String username) {
        // 刻意不捕获：失效失败意味着旧授权还在，调用方必须知道。
        redisTemplate.delete(key(username));
    }

    @Override
    public void evictAll() {
        // 用 SCAN 而不是 KEYS：KEYS 会阻塞整个 Redis 实例，而这套缓存和会话很可能共用一个实例，
        // 一次角色编辑不该让所有人的请求跟着卡住。
        List<String> batch = new ArrayList<>(SCAN_BATCH);
        int removed = 0;
        try (Cursor<String> cursor = redisTemplate.scan(
                ScanOptions.scanOptions().match(KEY_PREFIX + "*").count(SCAN_BATCH).build())) {
            while (cursor.hasNext()) {
                batch.add(cursor.next());
                if (batch.size() >= SCAN_BATCH) {
                    removed += delete(batch);
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            removed += delete(batch);
        }
        log.info("permission cache cleared, scope=redis, entries={}", removed);
    }

    private int delete(List<String> keys) {
        Long count = redisTemplate.delete(keys);
        return count == null ? 0 : count.intValue();
    }

    private String key(String username) {
        return KEY_PREFIX + username;
    }
}
