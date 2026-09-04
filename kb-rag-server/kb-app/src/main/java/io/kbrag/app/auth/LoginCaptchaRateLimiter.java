package io.kbrag.app.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.config.KbProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private static final String GLOBAL_ISSUE_SUBJECT = "global";
    private static final int DEFAULT_GLOBAL_ISSUE_LIMIT = 120;
    private static final int DEFAULT_GENERATION_CONCURRENCY = 2;

    private final Cache<BucketKey, AtomicInteger> counters;
    private final int globalIssueLimit;
    private final Semaphore generationBulkhead;

    @Autowired
    public LoginCaptchaRateLimiter(KbProperties properties) {
        this(Ticker.systemTicker(),
                properties.getAuth().getCaptcha().getGlobalIssueRateLimitPerMinute(),
                properties.getAuth().getCaptcha().getMaxGenerationConcurrency());
    }

    LoginCaptchaRateLimiter(Ticker ticker) {
        this(ticker, DEFAULT_GLOBAL_ISSUE_LIMIT, DEFAULT_GENERATION_CONCURRENCY);
    }

    LoginCaptchaRateLimiter(Ticker ticker, int globalIssueLimit, int generationConcurrency) {
        if (globalIssueLimit <= 0) {
            throw new IllegalArgumentException("global captcha issue limit must be positive");
        }
        if (generationConcurrency <= 0) {
            throw new IllegalArgumentException("captcha generation concurrency must be positive");
        }
        this.globalIssueLimit = globalIssueLimit;
        this.generationBulkhead = new Semaphore(generationConcurrency, true);
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
        acquireBucket(action, subjectHash, action.maxRequestsPerWindow);
    }

    /**
     * 为一次图片生成同时申请来源额度、进程全局额度和并发许可。
     *
     * <p>许可只包围 CPU 密集的 PNG 生成，不包围缓存写入或网络响应。
     */
    public IssuePermit acquireIssue(String subjectHash) {
        acquireBucket(Action.ISSUE, subjectHash, Action.ISSUE.maxRequestsPerWindow);
        acquireBucket(Action.ISSUE, GLOBAL_ISSUE_SUBJECT, globalIssueLimit);
        if (!generationBulkhead.tryAcquire()) {
            log.info("login captcha call rejected by generation bulkhead, errorCode={}",
                    ErrorCode.RATE_LIMITED);
            throw new BizException(ErrorCode.RATE_LIMITED, "验证码生成繁忙，请稍后重试");
        }
        return new IssuePermit(generationBulkhead);
    }

    private void acquireBucket(Action action, String subjectHash, int limit) {
        BucketKey key = new BucketKey(action, subjectHash);
        AtomicInteger counter = counters.get(key, ignored -> new AtomicInteger());
        if (counter != null && counter.incrementAndGet() <= limit) {
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

    /** 一次图片生成许可。重复关闭不会错误释放额外信号量。 */
    public static final class IssuePermit implements AutoCloseable {

        private final Semaphore semaphore;
        private final AtomicBoolean closed = new AtomicBoolean();

        private IssuePermit(Semaphore semaphore) {
            this.semaphore = semaphore;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                semaphore.release();
            }
        }
    }
}
