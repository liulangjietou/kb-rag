package io.kbrag.infrastructure.auth;

import io.kbrag.domain.model.UserPrincipal;
import io.kbrag.domain.port.PrincipalCache;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内的权限缓存，{@code kb.cache.provider=local} 下使用。
 *
 * <p>这是单节点部署的最优解，也是这套系统一直以来的实现：一次 Map 查找，没有序列化、没有网络往返，
 * 也没有一致性问题——只有一个进程在读写它，失效即刻可见。
 *
 * <p>不设过期时间：条目由显式失效清除，而进程重启会把整份缓存一起带走，所以它不会无限增长。
 * 共享实现没有这个性质，那边需要另想办法，见 {@link RedisPrincipalCache}。
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
public class LocalPrincipalCache implements PrincipalCache {

    /** 登录名 -> 已拍平的权限。 */
    private final Map<String, UserPrincipal> cache = new ConcurrentHashMap<>();

    @Override
    public UserPrincipal get(String username) {
        return cache.get(username);
    }

    @Override
    public void put(String username, UserPrincipal principal) {
        cache.put(username, principal);
    }

    @Override
    public void evict(String username) {
        cache.remove(username);
    }

    @Override
    public void evictAll() {
        cache.clear();
        log.info("permission cache cleared, scope=local");
    }
}
