package io.kbrag.app.registration;

import io.kbrag.domain.entity.MailOutbox;
import io.kbrag.domain.mapper.MailOutboxMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 固化 outbox lease 的并发唯一性与进程崩溃后的到期恢复。
 *
 * @author owlzhangfq@gmail.com
 */
class MailOutboxLeaseServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 31, 8, 0);

    @Test
    void shouldRecoverTheTaskOnlyAfterItsBoundedLeaseExpires() {
        MailOutboxMapper mapper = mock(MailOutboxMapper.class);
        RegistrationProperties properties = properties();
        AtomicReference<LocalDateTime> availableAt =
                new AtomicReference<>(NOW.minusSeconds(1));
        AtomicInteger version = new AtomicInteger(2);
        when(mapper.selectReadyBatch(any(LocalDateTime.class), eq(1), eq(5)))
                .thenAnswer(invocation -> {
                    LocalDateTime readyAt = invocation.getArgument(0);
                    return availableAt.get().isAfter(readyAt)
                            ? List.of() : List.of(task(version.get()));
                });
        when(mapper.claimDeliveryLease(anyString(), any(LocalDateTime.class),
                any(LocalDateTime.class), eq(5), anyInt())).thenAnswer(invocation -> {
                    LocalDateTime readyAt = invocation.getArgument(1);
                    LocalDateTime leaseUntil = invocation.getArgument(2);
                    int expectedVersion = invocation.getArgument(4);
                    if (availableAt.get().isAfter(readyAt)
                            || !version.compareAndSet(expectedVersion, expectedVersion + 1)) {
                        return 0;
                    }
                    availableAt.set(leaseUntil);
                    return 1;
                });
        MailOutboxLeaseService service = new MailOutboxLeaseService(mapper, properties);

        Optional<MailOutbox> first = service.claimNext(NOW);
        Optional<MailOutbox> beforeExpiry = service.claimNext(NOW.plusSeconds(59));
        Optional<MailOutbox> recovered = service.claimNext(NOW.plusSeconds(61));

        assertTrue(first.isPresent());
        assertEquals(NOW.plusSeconds(60), first.orElseThrow().getNextRetryAt());
        assertEquals(Integer.valueOf(3), first.orElseThrow().getLockVersion());
        assertTrue(beforeExpiry.isEmpty());
        assertTrue(recovered.isPresent());
        assertEquals(Integer.valueOf(4), recovered.orElseThrow().getLockVersion());
    }

    @Test
    void shouldExposeOnlyOneLeaseWhenTwoWorkersRaceTheSameVersion() throws Exception {
        MailOutboxMapper mapper = mock(MailOutboxMapper.class);
        RegistrationProperties properties = properties();
        AtomicInteger persistedVersion = new AtomicInteger(7);
        CountDownLatch selected = new CountDownLatch(2);
        CountDownLatch releaseClaims = new CountDownLatch(1);
        when(mapper.selectReadyBatch(NOW, 1, 5)).thenAnswer(invocation -> {
            MailOutbox candidate = task(7);
            selected.countDown();
            await(releaseClaims);
            return List.of(candidate);
        });
        when(mapper.claimDeliveryLease(anyString(), eq(NOW), eq(NOW.plusSeconds(60)),
                eq(5), eq(7))).thenAnswer(invocation ->
                persistedVersion.compareAndSet(7, 8) ? 1 : 0);
        MailOutboxLeaseService service = new MailOutboxLeaseService(mapper, properties);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<MailOutbox>> first = executor.submit(() -> service.claimNext(NOW));
            Future<Optional<MailOutbox>> second = executor.submit(() -> service.claimNext(NOW));
            assertTrue(selected.await(2, TimeUnit.SECONDS));
            releaseClaims.countDown();

            int leases = (first.get(5, TimeUnit.SECONDS).isPresent() ? 1 : 0)
                    + (second.get(5, TimeUnit.SECONDS).isPresent() ? 1 : 0);
            assertEquals(1, leases);
            assertEquals(8, persistedVersion.get());
        } finally {
            releaseClaims.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void shouldRejectAnUnboundedLeaseConfiguration() {
        RegistrationProperties properties = properties();
        properties.getOutbox().setLeaseSeconds(301);

        assertThrows(IllegalArgumentException.class,
                () -> new MailOutboxLeaseService(mock(MailOutboxMapper.class), properties));
    }

    private RegistrationProperties properties() {
        RegistrationProperties properties = new RegistrationProperties();
        properties.getOutbox().setMaxRetries(5);
        properties.getOutbox().setLeaseSeconds(60);
        return properties;
    }

    private MailOutbox task(int version) {
        MailOutbox task = new MailOutbox();
        task.setOutboxId("mail_1");
        task.setLockVersion(version);
        task.setRetryCount(0);
        return task;
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
