package io.kbrag.app.auth;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginAttemptGuardTest {

    @Test
    void shouldSerializeAttemptsForTheSameUsernameAcrossAddresses() throws Exception {
        assertMutuallyExclusive("alice", "203.0.113.1", "alice", "203.0.113.2");
    }

    @Test
    void shouldSerializeAttemptsForTheSameAddressAcrossUsernames() throws Exception {
        assertMutuallyExclusive("alice", "203.0.113.1", "bob", "203.0.113.1");
    }

    @Test
    void shouldAcquireMixedPairsWithoutDeadlock() {
        LoginAttemptGuard guard = new LoginAttemptGuard(16);
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            ExecutorService executor = Executors.newFixedThreadPool(8);
            try {
                Future<?>[] futures = new Future<?>[64];
                for (int index = 0; index < futures.length; index++) {
                    int attempt = index;
                    futures[index] = executor.submit(() -> {
                        try (LoginAttemptGuard.Permit ignored = guard.acquire(
                                "user-" + attempt % 7, "198.51.100." + attempt % 5)) {
                            Thread.yield();
                        }
                    });
                }
                for (Future<?> future : futures) {
                    future.get();
                }
            } finally {
                executor.shutdownNow();
            }
        });
    }

    @Test
    void shouldNotCoupleKnownJavaStringHashCollisions() throws Exception {
        // "Aa" 与 "BB" 的 String.hashCode 相同；随机盐 SHA-256 后不应再落入同一条带。
        LoginAttemptGuard guard = new LoginAttemptGuard(4_096, "test-salt");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        LoginAttemptGuard.Permit first = guard.acquire("Aa", "203.0.113.1");
        CountDownLatch acquired = new CountDownLatch(1);
        try {
            Future<?> contender = executor.submit(() -> {
                try (LoginAttemptGuard.Permit ignored = guard.acquire("BB", "203.0.113.2")) {
                    acquired.countDown();
                }
            });

            assertTrue(acquired.await(1, TimeUnit.SECONDS));
            contender.get(1, TimeUnit.SECONDS);
        } finally {
            first.close();
            executor.shutdownNow();
        }
    }

    private void assertMutuallyExclusive(String firstUsername, String firstIp,
                                         String secondUsername, String secondIp) throws Exception {
        LoginAttemptGuard guard = new LoginAttemptGuard(16);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        LoginAttemptGuard.Permit first = guard.acquire(firstUsername, firstIp);
        CountDownLatch attempting = new CountDownLatch(1);
        CountDownLatch acquired = new CountDownLatch(1);
        try {
            Future<?> contender = executor.submit(() -> {
                attempting.countDown();
                try (LoginAttemptGuard.Permit ignored = guard.acquire(secondUsername, secondIp)) {
                    acquired.countDown();
                }
            });

            assertTrue(attempting.await(1, TimeUnit.SECONDS));
            assertFalse(acquired.await(150, TimeUnit.MILLISECONDS));
            first.close();
            first = null;
            assertTrue(acquired.await(1, TimeUnit.SECONDS));
            contender.get(1, TimeUnit.SECONDS);
        } finally {
            if (first != null) {
                first.close();
            }
            executor.shutdownNow();
        }
    }
}
