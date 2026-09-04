package io.kbrag.app.registration;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.HashUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 注册匿名动作的进程内分层固定窗口限流器。
 *
 * <p>邮箱与来源地址在进入缓存前均被摘要，日志只记录被触发的层级。实例级同步让一次请求
 * 对邮箱、IP 和全局三个计数器的检查与递增成为一个原子操作。
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
public class RegistrationRateLimiter {

    private static final int MAX_COUNTERS = 100_000;
    private static final long HOUR_SECONDS = Duration.ofHours(1).toSeconds();
    private static final long MINUTE_SECONDS = Duration.ofMinutes(1).toSeconds();

    private final RegistrationProperties properties;
    private final Clock clock;
    private final Cache<String, FixedWindowCounter> counters;

    @Autowired
    public RegistrationRateLimiter(RegistrationProperties properties) {
        this(properties, Clock.systemUTC());
    }

    RegistrationRateLimiter(RegistrationProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.counters = Caffeine.newBuilder()
                .expireAfterAccess(Duration.ofHours(2))
                .maximumSize(MAX_COUNTERS)
                .build();
    }

    /**
     * 原子消费一次发码配额。
     *
     * @param normalizedEmail 已标准化邮箱
     * @param clientIp        可信边界解析后的客户端地址
     */
    public synchronized void acquireCodeRequest(String normalizedEmail, String clientIp) {
        long now = clock.instant().getEpochSecond();
        CounterRequest email = new CounterRequest(
                "code:email:" + HashUtil.sha256Hex(normalizedEmail),
                now / HOUR_SECONDS,
                positive(properties.getEmailRateLimitPerHour()),
                "code-email");
        CounterRequest ip = new CounterRequest(
                "code:ip:" + sourceHash(clientIp),
                now / HOUR_SECONDS,
                positive(properties.getIpRateLimitPerHour()),
                "code-ip");
        CounterRequest global = new CounterRequest(
                "code:global",
                now / MINUTE_SECONDS,
                positive(properties.getGlobalRateLimitPerMinute()),
                "code-global");

        acquire(email, ip, global);
    }

    /**
     * 原子消费一次验证码校验配额；必须在查询验证码状态前调用。
     *
     * @param clientIp 可信边界解析后的客户端地址
     */
    public synchronized void acquireVerificationAttempt(String clientIp) {
        long now = clock.instant().getEpochSecond();
        acquire(
                new CounterRequest("verify:ip:" + sourceHash(clientIp),
                        now / HOUR_SECONDS,
                        positive(properties.getVerifyIpRateLimitPerHour()),
                        "verify-ip"),
                new CounterRequest("verify:global",
                        now / MINUTE_SECONDS,
                        positive(properties.getVerifyGlobalRateLimitPerMinute()),
                        "verify-global"));
    }

    /**
     * 原子消费一次最终注册提交配额；必须在查询票据前调用。
     *
     * @param clientIp 可信边界解析后的客户端地址
     */
    public synchronized void acquireSubmissionAttempt(String clientIp) {
        long now = clock.instant().getEpochSecond();
        acquire(
                new CounterRequest("submit:ip:" + sourceHash(clientIp),
                        now / HOUR_SECONDS,
                        positive(properties.getSubmitIpRateLimitPerHour()),
                        "submit-ip"),
                new CounterRequest("submit:global",
                        now / MINUTE_SECONDS,
                        positive(properties.getSubmitGlobalRateLimitPerMinute()),
                        "submit-global"));
    }

    private void acquire(CounterRequest... requests) {
        for (CounterRequest request : requests) {
            requireAvailable(request);
        }
        for (CounterRequest request : requests) {
            increment(request);
        }
    }

    private String sourceHash(String clientIp) {
        return HashUtil.sha256Hex(clientIp == null ? "" : clientIp);
    }

    private void requireAvailable(CounterRequest request) {
        FixedWindowCounter current = counters.getIfPresent(request.key());
        int count = current == null || current.window() != request.window()
                ? 0 : current.count().get();
        if (count >= request.limit()) {
            log.info("registration rate limit reached, errorCode={}, layer={}",
                    ErrorCode.RATE_LIMITED, request.layer());
            throw new BizException(ErrorCode.RATE_LIMITED, "操作过于频繁，请稍后重试");
        }
    }

    private void increment(CounterRequest request) {
        counters.asMap().compute(request.key(), (key, current) ->
                current == null || current.window() != request.window()
                        ? new FixedWindowCounter(request.window(), new AtomicInteger(1))
                        : incremented(current));
    }

    private FixedWindowCounter incremented(FixedWindowCounter current) {
        current.count().incrementAndGet();
        return current;
    }

    private int positive(int value) {
        return Math.max(1, value);
    }

    /**
     * 一次配额消费对应的缓存键、窗口号与上限。
     *
     * @author owlzhangfq@gmail.com
     */
    private record CounterRequest(String key, long window, int limit, String layer) {
    }

    /**
     * 固定窗口计数器；原子计数配合方法级同步，保证三层配额一次提交。
     *
     * @author owlzhangfq@gmail.com
     */
    private record FixedWindowCounter(long window, AtomicInteger count) {
    }
}
