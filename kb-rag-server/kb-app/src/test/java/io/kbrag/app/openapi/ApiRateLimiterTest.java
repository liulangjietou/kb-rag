package io.kbrag.app.openapi;

import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins down the per key rate limit of requirement section 4.8, including the refill behaviour a fixed window
 * counter would get wrong.
 *
 * <p>Time is injected rather than slept through, so the assertions are exact instead of timing dependent.
 *
 * @author owlzhangfq@gmail.com
 */
class ApiRateLimiterTest {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final String KEY_ID = "ak_test";
    private static final int RETRY_AFTER = 1;

    private AtomicLong clock;
    private ApiRateLimiter limiter;

    @BeforeEach
    void setUp() {
        clock = new AtomicLong(0L);
        limiter = new ApiRateLimiter(clock::get);
    }

    @Test
    void shouldAllowExactlyTheConfiguredQuotaWithinOneSecond() {
        limiter.acquire(KEY_ID, 3, RETRY_AFTER);
        limiter.acquire(KEY_ID, 3, RETRY_AFTER);
        limiter.acquire(KEY_ID, 3, RETRY_AFTER);

        BizException e = assertThrows(BizException.class, () -> limiter.acquire(KEY_ID, 3, RETRY_AFTER));

        assertEquals(ErrorCode.RATE_LIMITED, e.getErrorCode());
        assertEquals(429, e.getErrorCode().getHttpStatus());
        assertTrue(e.getMessage().contains("3"));
    }

    @Test
    void shouldRefillOverTimeRatherThanAtAWindowBoundary() {
        limiter.acquire(KEY_ID, 2, RETRY_AFTER);
        limiter.acquire(KEY_ID, 2, RETRY_AFTER);
        assertThrows(BizException.class, () -> limiter.acquire(KEY_ID, 2, RETRY_AFTER));

        // Half a second at 2 per second earns exactly one token.
        clock.set(NANOS_PER_SECOND / 2);
        assertDoesNotThrow(() -> limiter.acquire(KEY_ID, 2, RETRY_AFTER));
        assertThrows(BizException.class, () -> limiter.acquire(KEY_ID, 2, RETRY_AFTER));
    }

    @Test
    void shouldNeverAllowABurstLargerThanOneSecondOfQuota() {
        limiter.acquire(KEY_ID, 2, RETRY_AFTER);
        limiter.acquire(KEY_ID, 2, RETRY_AFTER);

        // Ten idle seconds must not accumulate twenty tokens: the bucket is capped at its rate.
        clock.set(10 * NANOS_PER_SECOND);
        limiter.acquire(KEY_ID, 2, RETRY_AFTER);
        limiter.acquire(KEY_ID, 2, RETRY_AFTER);

        assertThrows(BizException.class, () -> limiter.acquire(KEY_ID, 2, RETRY_AFTER));
    }

    @Test
    void shouldTrackEachKeySeparately() {
        limiter.acquire("ak_a", 1, RETRY_AFTER);
        assertThrows(BizException.class, () -> limiter.acquire("ak_a", 1, RETRY_AFTER));

        assertDoesNotThrow(() -> limiter.acquire("ak_b", 1, RETRY_AFTER));
    }

    @Test
    void shouldStartAgainWithAFullBucketAfterTheKeyWasForgotten() {
        limiter.acquire(KEY_ID, 1, RETRY_AFTER);
        assertThrows(BizException.class, () -> limiter.acquire(KEY_ID, 1, RETRY_AFTER));

        // A rotation or a status change drops the bucket, so the caller is not additionally throttled by a
        // bucket the previous secret drained.
        limiter.forget(KEY_ID);

        assertDoesNotThrow(() -> limiter.acquire(KEY_ID, 1, RETRY_AFTER));
    }

    @Test
    void shouldTreatANonPositiveQuotaAsOnePerSecond() {
        limiter.acquire(KEY_ID, 0, RETRY_AFTER);

        assertThrows(BizException.class, () -> limiter.acquire(KEY_ID, 0, RETRY_AFTER));
    }
}
