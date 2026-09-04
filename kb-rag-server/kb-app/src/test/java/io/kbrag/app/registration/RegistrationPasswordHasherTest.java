package io.kbrag.app.registration;

import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 固化匿名 BCrypt 的非等待 CPU 舱壁。
 *
 * @author owlzhangfq@gmail.com
 */
class RegistrationPasswordHasherTest {

    @Test
    void shouldRejectImmediatelyWhenAllHashSlotsAreOccupied() throws Exception {
        RegistrationProperties properties = new RegistrationProperties();
        properties.setPasswordHashConcurrency(1);
        BCryptPasswordEncoder encoder = mock(BCryptPasswordEncoder.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            if (!released.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test hash wait timed out");
            }
            return "bcrypt-first";
        }).when(encoder).encode("StrongPassword!1");
        RegistrationPasswordHasher hasher = new RegistrationPasswordHasher(encoder, properties);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> first = executor.submit(() -> hasher.hash("StrongPassword!1"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            BizException limited = assertThrows(BizException.class,
                    () -> hasher.hash("AnotherPassword!2"));

            assertEquals(ErrorCode.RATE_LIMITED, limited.getErrorCode());
            verify(encoder, never()).encode("AnotherPassword!2");
            released.countDown();
            assertEquals("bcrypt-first", first.get(5, TimeUnit.SECONDS));
        } finally {
            released.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void shouldRejectUnsafeConcurrencyConfiguration() {
        BCryptPasswordEncoder encoder = mock(BCryptPasswordEncoder.class);
        RegistrationProperties properties = new RegistrationProperties();
        properties.setPasswordHashConcurrency(0);

        assertThrows(IllegalArgumentException.class,
                () -> new RegistrationPasswordHasher(encoder, properties));

        properties.setPasswordHashConcurrency(5);
        assertThrows(IllegalArgumentException.class,
                () -> new RegistrationPasswordHasher(encoder, properties));
    }
}
