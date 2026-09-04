package io.kbrag.app.registration;

import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 固化同步 SMTP 舱壁在数据库事务前的非等待并发上限。
 *
 * @author owlzhangfq@gmail.com
 */
class RegistrationMailBulkheadTest {

    @Test
    void shouldRejectImmediatelyAtCapacityAndReleaseThePermitAfterCompletion() throws Exception {
        RegistrationProperties properties = new RegistrationProperties();
        properties.setMailConcurrency(1);
        RegistrationMailBulkhead bulkhead = new RegistrationMailBulkhead(properties);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> first = executor.submit(() -> bulkhead.execute(() -> {
                entered.countDown();
                await(release);
                return "first";
            }));
            assertTrue(entered.await(2, TimeUnit.SECONDS));

            BizException limited = assertThrows(BizException.class,
                    () -> bulkhead.execute(() -> "unexpected"));
            assertEquals(ErrorCode.RATE_LIMITED, limited.getErrorCode());

            release.countDown();
            assertEquals("first", first.get(5, TimeUnit.SECONDS));
            assertEquals("next", bulkhead.execute(() -> "next"));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void shouldFailFastWhenConfiguredAtOrAboveTheDefaultDatabasePool() {
        RegistrationProperties properties = new RegistrationProperties();
        properties.setMailConcurrency(10);

        assertThrows(IllegalArgumentException.class,
                () -> new RegistrationMailBulkhead(properties));
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test latch timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test thread interrupted", exception);
        }
    }
}
