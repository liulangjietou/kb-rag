package io.kbrag.app.registration;

import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 固化注册发码的邮箱、IP、全局三层原子固定窗口限流。
 *
 * @author owlzhangfq@gmail.com
 */
class RegistrationRateLimiterTest {

    @Test
    void shouldBeCreatedBySpringWithTheRuntimeConstructor() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(
                             RegistrationProperties.class, RegistrationRateLimiter.class)) {
            assertTrue(context.containsBean("registrationRateLimiter"));
        }
    }

    @Test
    void shouldEnforceEmailAndIpLayersAndResetAtTheNextHour() {
        RegistrationProperties properties = permissiveProperties();
        properties.setEmailRateLimitPerHour(1);
        properties.setIpRateLimitPerHour(1);
        MutableClock clock = new MutableClock(Instant.parse("2026-08-31T08:00:00Z"));
        RegistrationRateLimiter limiter = new RegistrationRateLimiter(properties, clock);

        limiter.acquireCodeRequest("first@example.com", "203.0.113.1");
        BizException emailLimited = assertThrows(BizException.class,
                () -> limiter.acquireCodeRequest("first@example.com", "203.0.113.2"));
        BizException ipLimited = assertThrows(BizException.class,
                () -> limiter.acquireCodeRequest("second@example.com", "203.0.113.1"));

        assertEquals(ErrorCode.RATE_LIMITED, emailLimited.getErrorCode());
        assertEquals(ErrorCode.RATE_LIMITED, ipLimited.getErrorCode());
        clock.advanceSeconds(3_600);
        assertDoesNotThrow(() -> limiter.acquireCodeRequest("first@example.com", "203.0.113.1"));
    }

    @Test
    void shouldAtomicallyAdmitOnlyTheConfiguredGlobalCount() throws Exception {
        RegistrationProperties properties = permissiveProperties();
        properties.setGlobalRateLimitPerMinute(5);
        RegistrationRateLimiter limiter = new RegistrationRateLimiter(properties,
                Clock.fixed(Instant.parse("2026-08-31T08:00:00Z"), ZoneOffset.UTC));
        int attempts = 20;
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int index = 0; index < attempts; index++) {
                int current = index;
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        limiter.acquireCodeRequest("person-" + current + "@example.com",
                                "203.0.113." + current);
                        return true;
                    } catch (BizException exception) {
                        assertEquals(ErrorCode.RATE_LIMITED, exception.getErrorCode());
                        return false;
                    }
                }));
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();
            int admitted = 0;
            for (Future<Boolean> result : results) {
                if (result.get(5, TimeUnit.SECONDS)) {
                    admitted++;
                }
            }
            assertEquals(5, admitted);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void shouldLimitVerificationByIpAndGlobalWindow() {
        RegistrationProperties ipProperties = permissiveProperties();
        ipProperties.setVerifyIpRateLimitPerHour(1);
        RegistrationRateLimiter ipLimiter = new RegistrationRateLimiter(ipProperties,
                Clock.fixed(Instant.parse("2026-08-31T08:00:00Z"), ZoneOffset.UTC));

        ipLimiter.acquireVerificationAttempt("203.0.113.1");
        BizException ipLimited = assertThrows(BizException.class,
                () -> ipLimiter.acquireVerificationAttempt("203.0.113.1"));

        RegistrationProperties globalProperties = permissiveProperties();
        globalProperties.setVerifyGlobalRateLimitPerMinute(1);
        RegistrationRateLimiter globalLimiter = new RegistrationRateLimiter(globalProperties,
                Clock.fixed(Instant.parse("2026-08-31T08:00:00Z"), ZoneOffset.UTC));
        globalLimiter.acquireVerificationAttempt("203.0.113.1");
        BizException globalLimited = assertThrows(BizException.class,
                () -> globalLimiter.acquireVerificationAttempt("203.0.113.2"));

        assertEquals(ErrorCode.RATE_LIMITED, ipLimited.getErrorCode());
        assertEquals(ErrorCode.RATE_LIMITED, globalLimited.getErrorCode());
    }

    @Test
    void shouldKeepSubmissionBudgetIndependentFromVerification() {
        RegistrationProperties properties = permissiveProperties();
        properties.setVerifyIpRateLimitPerHour(1);
        properties.setSubmitIpRateLimitPerHour(1);
        RegistrationRateLimiter limiter = new RegistrationRateLimiter(properties,
                Clock.fixed(Instant.parse("2026-08-31T08:00:00Z"), ZoneOffset.UTC));

        limiter.acquireVerificationAttempt("203.0.113.1");
        assertDoesNotThrow(() -> limiter.acquireSubmissionAttempt("203.0.113.1"));
        BizException limited = assertThrows(BizException.class,
                () -> limiter.acquireSubmissionAttempt("203.0.113.1"));

        assertEquals(ErrorCode.RATE_LIMITED, limited.getErrorCode());
    }

    @Test
    void shouldLimitSubmissionByItsOwnGlobalWindow() {
        RegistrationProperties properties = permissiveProperties();
        properties.setSubmitGlobalRateLimitPerMinute(1);
        RegistrationRateLimiter limiter = new RegistrationRateLimiter(properties,
                Clock.fixed(Instant.parse("2026-08-31T08:00:00Z"), ZoneOffset.UTC));

        limiter.acquireSubmissionAttempt("203.0.113.1");
        BizException limited = assertThrows(BizException.class,
                () -> limiter.acquireSubmissionAttempt("203.0.113.2"));

        assertEquals(ErrorCode.RATE_LIMITED, limited.getErrorCode());
    }

    private RegistrationProperties permissiveProperties() {
        RegistrationProperties properties = new RegistrationProperties();
        properties.setEmailRateLimitPerHour(100);
        properties.setIpRateLimitPerHour(100);
        properties.setGlobalRateLimitPerMinute(100);
        properties.setVerifyIpRateLimitPerHour(100);
        properties.setVerifyGlobalRateLimitPerMinute(100);
        properties.setSubmitIpRateLimitPerHour(100);
        properties.setSubmitGlobalRateLimitPerMinute(100);
        return properties;
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }
    }
}
