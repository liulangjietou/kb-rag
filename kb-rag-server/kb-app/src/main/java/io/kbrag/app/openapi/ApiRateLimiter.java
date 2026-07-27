package io.kbrag.app.openapi;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * Per key rate limiting of the open API, requirement section 4.8 "rate limit per API key".
 *
 * <p><b>In process on purpose.</b> The Redis boundary of this system lists rate limit counters as one of three
 * volatile data types, with the explicit rule that a single instance deployment degrades to an in process
 * implementation and that the absence of Redis affects scale out, never correctness. This is that
 * implementation; a distributed one would divide the quota across instances and is a scale out concern.
 *
 * <p>Buckets are cached with an idle expiry, so a key that stopped calling releases its bucket instead of
 * leaking one entry per key that ever authenticated.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
public class ApiRateLimiter {

    /** Idle window after which a bucket is dropped; a returning caller simply gets a fresh, full bucket. */
    private static final Duration BUCKET_IDLE_TTL = Duration.ofMinutes(10);

    /** Upper bound of tracked keys, a memory guard rather than a policy. */
    private static final int MAX_TRACKED_KEYS = 10_000;

    private final Cache<String, TokenBucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(BUCKET_IDLE_TTL)
            .maximumSize(MAX_TRACKED_KEYS)
            .build();

    private final LongSupplier clock;

    public ApiRateLimiter() {
        this(System::nanoTime);
    }

    /**
     * Creates a limiter reading time from a supplied clock, which is what lets a test move time forward
     * without sleeping.
     *
     * @param clock nanosecond clock
     */
    public ApiRateLimiter(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * Consumes one permit of a key's quota, or rejects the call.
     *
     * @param keyId        API key business id
     * @param qpsLimit     permitted requests per second of that key
     * @param retryAfterSeconds value advertised in the {@code Retry-After} header of the rejection
     * @throws BizException with {@code RATE_LIMITED} when the bucket is empty
     */
    public void acquire(String keyId, int qpsLimit, int retryAfterSeconds) {
        long now = clock.getAsLong();
        TokenBucket bucket = buckets.get(keyId, id -> new TokenBucket(qpsLimit, now));
        if (bucket != null && bucket.tryAcquire(now)) {
            return;
        }
        log.info("open api call rejected by the rate limit, errorCode={}, keyId={}, qpsLimit={}",
                ErrorCode.RATE_LIMITED, keyId, qpsLimit);
        throw new BizException(ErrorCode.RATE_LIMITED,
                "调用频率超过该 API Key 的配额（" + qpsLimit + " QPS），请在 " + retryAfterSeconds + " 秒后重试");
    }

    /**
     * Forgets a key's bucket, used when a key is rotated or disabled so a new secret starts with full quota.
     *
     * @param keyId API key business id
     */
    public void forget(String keyId) {
        buckets.invalidate(keyId);
    }
}
