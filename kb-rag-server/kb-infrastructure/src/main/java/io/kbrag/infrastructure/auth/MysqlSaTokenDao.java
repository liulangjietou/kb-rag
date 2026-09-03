package io.kbrag.infrastructure.auth;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.auto.SaTokenDaoByObjectFollowString;
import cn.dev33.satoken.util.SaFoxUtil;
import io.kbrag.domain.mapper.AuthSessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.ArrayList;
import java.util.List;

/**
 * Sa-Token 的 MySQL 存储实现，{@code kb.cache.provider=local} 下的会话底座。
 *
 * <p><b>为什么单实例部署没有直接用 Sa-Token 自带的内存实现。</b> 框架默认的 {@code SaTokenDaoDefaultImpl}
 * 把会话放在进程内的 Map 里，进程一重启全体掉线。而本项目在引入 Sa-Token 之前，自建的会话存储就已经是
 * 数据库落地的（见已删除的 V11 迁移），重启不掉线是既有能力。换框架不该让用户体验倒退，所以单实例路径
 * 补上这个实现，把"重启不掉线"接着提供下去；多实例场景则交给 {@code cache.provider=redis}。
 *
 * <p>只实现了字符串一组方法。{@link SaTokenDaoByObjectFollowString} 会把对象读写转发成字符串读写、
 * 再把会话读写转发成对象读写，序列化由框架的 {@code SaManager} 统一负责，因此这里既不需要碰 JSON，
 * 也不会和框架的序列化配置产生第二套约定。
 *
 * <p>过期判定全部下沉到 SQL，理由见 {@link AuthSessionMapper} 的类注释。
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@RequiredArgsConstructor
public class MysqlSaTokenDao implements SaTokenDaoByObjectFollowString {

    /** Sa-Token 约定的"不存储"上界：{@code timeout} 为 0 或小于等于 -2 时调用方并不想写入。 */
    private static final long NO_STORE_TIMEOUT = 0L;

    private final AuthSessionMapper authSessionMapper;

    @Override
    public String get(String key) {
        return authSessionMapper.selectValue(key);
    }

    @Override
    public void set(String key, String value, long timeout) {
        // 与官方 Redis 实现逐字对齐：0 和 <= -2 都表示"不要写"，只有 -1 才是永久。
        // 少判一个分支就会把一次性票据写成永久会话，这里的等价性比简洁更重要。
        if (timeout == NO_STORE_TIMEOUT || timeout <= SaTokenDao.NOT_VALUE_EXPIRE) {
            return;
        }
        authSessionMapper.upsert(key, value, timeout);
    }

    @Override
    public void update(String key, String value) {
        // 键不存在时什么都不做，对应 Redis 的 SET XX KEEPTTL。改成 upsert 会让已登出的会话被续期复活。
        authSessionMapper.updateValueKeepTtl(key, value);
    }

    @Override
    public void delete(String key) {
        authSessionMapper.deleteByKey(key);
    }

    @Override
    public long getTimeout(String key) {
        Long ttl = authSessionMapper.selectTtlSeconds(key);
        return ttl == null ? SaTokenDao.NOT_VALUE_EXPIRE : ttl;
    }

    @Override
    public void updateTimeout(String key, long timeout) {
        if (timeout == SaTokenDao.NEVER_EXPIRE) {
            // 一条 UPDATE 就能把 expires_at 置空，不必像 Redis 实现那样先读回值再整体重写：
            // 那里绕这一圈是因为 Redis 的 EXPIRE 命令无法表达"取消过期"。
            authSessionMapper.updateTtl(key, SaTokenDao.NEVER_EXPIRE);
            return;
        }
        authSessionMapper.updateTtl(key, timeout);
    }

    @Override
    public List<String> searchData(String prefix, String keyword, int start, int size, boolean sortType) {
        String pattern = escapeLike(prefix) + "%" + escapeLike(keyword) + "%";
        List<String> keys = authSessionMapper.selectKeysLike(pattern);
        // 分页与排序沿用框架工具，保证和 Redis 实现在同样入参下给出同样的窗口。
        // 必须先复制一份：这个工具会就地排序，直接把查询结果交进去等于让它改写别人的返回值。
        return SaFoxUtil.searchList(new ArrayList<>(keys), start, size, sortType);
    }

    /**
     * 回收已过期的行。
     *
     * <p>Redis 到期即自动淘汰，换成 MySQL 之后这件事得有人做，否则会话表只增不减。清理放在定时任务里
     * 而不是挂在登录路径上：过期语义已经由每条语句里的 {@code NOW()} 比较保证，这里只回收空间，
     * 因此它什么时候跑、跑没跑成功都不影响正确性，没有理由让用户的登录请求为它多等一次删除。
     *
     * <p>周期给了配置占位符但不打算让人去调：会话表的增长速度由登录频率决定，每小时一次对任何规模都
     * 够用，留出口只是为了极端情况下不必改代码。
     *
     * @return 清理掉的行数
     */
    @Scheduled(cron = "${kb.cache.session-purge-cron:0 15 * * * *}")
    public int purgeExpired() {
        int purged = authSessionMapper.purgeExpired();
        if (purged > 0) {
            log.info("expired console sessions purged, rows={}", purged);
        }
        return purged;
    }

    /**
     * 转义键里本就存在的 LIKE 通配符。
     *
     * <p>Sa-Token 的检索语义里只有 {@code *} 是通配符，{@code %} 和 {@code _} 都是普通字符；而登录名
     * 里的下划线很常见。不转义的话 {@code a_b} 会连 {@code axb} 一起捞出来。
     */
    private String escapeLike(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        return raw.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
