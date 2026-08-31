package io.kbrag.app.auth;

import io.kbrag.common.util.HashUtil;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 单实例登录尝试并发守卫。
 *
 * <p>同一标准化用户名或同一来源地址的尝试必须串行完成“检查阈值—认证—写失败审计”，
 * 否则并发请求会一起越过失败阈值。固定数量的条带锁避免按用户名或 IP 持续创建锁对象；
 * 进程级随机盐的 SHA-256 映射避免攻击者利用可预测的 String hash 碰撞阻塞指定账号。
 * 多实例部署仍需将这一原子边界迁移到共享存储。
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class LoginAttemptGuard {

    private static final int DEFAULT_STRIPE_COUNT = 4_096;
    private static final int SALT_BYTES = 16;
    private static final String USERNAME_NAMESPACE = "username:";
    private static final String IP_NAMESPACE = "ip:";
    private static final String HASH_SEPARATOR = "\n";

    private final ReentrantLock[] stripes;
    private final String stripeSalt;

    public LoginAttemptGuard() {
        this(DEFAULT_STRIPE_COUNT, randomSalt());
    }

    LoginAttemptGuard(int stripeCount) {
        this(stripeCount, randomSalt());
    }

    LoginAttemptGuard(int stripeCount, String stripeSalt) {
        if (stripeCount <= 0 || Integer.bitCount(stripeCount) != 1) {
            throw new IllegalArgumentException("stripe count must be a positive power of two");
        }
        if (stripeSalt == null || stripeSalt.isBlank()) {
            throw new IllegalArgumentException("stripe salt must not be blank");
        }
        this.stripeSalt = stripeSalt;
        this.stripes = new ReentrantLock[stripeCount];
        for (int index = 0; index < stripeCount; index++) {
            stripes[index] = new ReentrantLock();
        }
    }

    /**
     * 同时取得用户名和来源地址对应的锁，并按条带下标固定排序以避免死锁。
     *
     * @param normalizedUsername 标准化后的用户名
     * @param resolvedIp         已解析的来源地址
     * @return 必须在 finally 或 try-with-resources 中释放的许可
     */
    Permit acquire(String normalizedUsername, String resolvedIp) {
        int usernameStripe = stripe(USERNAME_NAMESPACE, normalizedUsername);
        int ipStripe = stripe(IP_NAMESPACE, resolvedIp);
        int firstStripe = Math.min(usernameStripe, ipStripe);
        int secondStripe = Math.max(usernameStripe, ipStripe);

        stripes[firstStripe].lock();
        if (firstStripe != secondStripe) {
            stripes[secondStripe].lock();
        }
        return new Permit(stripes[firstStripe],
                firstStripe == secondStripe ? null : stripes[secondStripe]);
    }

    private int stripe(String namespace, String value) {
        String digest = HashUtil.sha256Hex(stripeSalt + HASH_SEPARATOR + namespace
                + (value == null ? "" : value));
        int hash = Integer.parseUnsignedInt(digest.substring(0, 8), 16);
        return hash & (stripes.length - 1);
    }

    private static String randomSalt() {
        byte[] bytes = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** 已取得的两维登录尝试许可。 */
    static final class Permit implements AutoCloseable {

        private final ReentrantLock first;
        private final ReentrantLock second;

        private Permit(ReentrantLock first, ReentrantLock second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public void close() {
            if (second != null) {
                second.unlock();
            }
            first.unlock();
        }
    }
}
