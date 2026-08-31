package io.kbrag.app.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 登录滑块专用的进程内固定窗口限流器。
 *
 * <p>验证码本身是匿名入口，不能复用以 API Key 为主体的限流器。这里以直连地址摘要为
 * 主体，并分别限制挑战签发、轨迹验证和 proof 消费；User-Agent 可由客户端任意改写，不能
 * 用来切分限流额度。缓存有容量上限和短过期时间，不会随匿名来源无限增长。
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
public class LoginCaptchaRateLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final int MAX_TRACKED_BUCKETS = 30_000;

    private final Cache<BucketKey, AtomicInteger> counters;

    public LoginCaptchaRateLimiter() {
        this(Ticker.systemTicker());
    }

    LoginCaptchaRateLimiter(Ticker ticker) {
        this.counters = Caffeine.newBuilder()
                .expireAfterWrite(WINDOW)
                .maximumSize(MAX_TRACKED_BUCKETS)
                .ticker(ticker)
                .build();
    }

    /**
     * 消耗指定操作的一次匿名额度。
     *
     * @param action      验证码阶段
     * @param subjectHash 直连地址摘要
     */
    public void acquire(Action action, String subjectHash) {
        BucketKey key = new BucketKey(action, subjectHash);
        AtomicInteger counter = counters.get(key, ignored -> new AtomicInteger());
        if (counter != null && counter.incrementAndGet() <= action.maxRequestsPerWindow) {
            return;
        }
        log.info("login captcha call rejected by rate limit, errorCode={}, action={}",
                ErrorCode.RATE_LIMITED, action);
        throw new BizException(ErrorCode.RATE_LIMITED, "操作过于频繁，请稍后重试");
    }

    /** 验证码各阶段的独立窗口额度。 */
    public enum Action {
        ISSUE(20),
        VERIFY(40),
        CONSUME(20);

        private final int maxRequestsPerWindow;

        Action(int maxRequestsPerWindow) {
            this.maxRequestsPerWindow = maxRequestsPerWindow;
        }
    }

    private record BucketKey(Action action, String subjectHash) {
    }
}
