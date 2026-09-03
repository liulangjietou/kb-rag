package io.kbrag.domain.port;

import io.kbrag.domain.model.UserPrincipal;

/**
 * 已拍平的用户权限的缓存。
 *
 * <p><b>为什么这件事值得抽成一个端口。</b> 把角色、授权、知识库范围join 成一份可判定的权限要四次查询，
 * 而这些行一个月只改动几次，所以它必须有缓存。但缓存放哪里，取决于这套部署有几个节点：单节点放进程内
 * 内存是最优解，没有一致性问题也没有网络开销；多节点就必须放共享存储，否则 A 节点改了角色，B 节点会
 * 继续按旧授权放行。两种答案都对，对的条件不同，因此是一个端口而不是一个实现。
 *
 * <p><b>失效必须是删除，不能是通知。</b> 实现共享缓存时不要改用"本地缓存 + 跨节点失效广播"：那样读更
 * 快，但广播是尽力而为的，一条消息在某个节点重连期间丢掉，那个节点就会一直用着旧授权，而且没有任何
 * 迹象。过期的授权是安全缺陷，不是性能问题——宁可每次多一次网络往返，也不能让正确性取决于消息是否送达。
 *
 * <p>实现必须是线程安全的：控制台的每个请求都会读它。
 *
 * @author owlzhangfq@gmail.com
 */
public interface PrincipalCache {

    /**
     * 读取一个账号已缓存的权限。
     *
     * @param username 登录名
     * @return 缓存的权限，未缓存时为 {@code null}
     */
    UserPrincipal get(String username);

    /**
     * 缓存一个账号的权限。
     *
     * @param username  登录名
     * @param principal 已拍平的权限
     */
    void put(String username, UserPrincipal principal);

    /**
     * 丢弃一个账号的缓存，在它的角色变动之后调用。
     *
     * @param username 登录名
     */
    void evict(String username);

    /**
     * 丢弃全部缓存，在角色定义或知识库范围变动之后调用。
     *
     * <p>刻意粗暴：改一个角色不去算谁持有它。算准要多一次查询，而算漏一个人就是一份过期的授权。
     */
    void evictAll();
}
