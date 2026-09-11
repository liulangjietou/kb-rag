package io.kbrag.domain.model;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChatCancellationTest {

    @Test
    void shouldCancelLateRegistrationsExactlyOnceAndForgetClosedOnes() {
        ChatCancellation cancellation = new ChatCancellation();
        AtomicInteger called = new AtomicInteger();
        cancellation.onCancel(called::incrementAndGet).close();
        cancellation.onCancel(called::incrementAndGet);
        cancellation.cancel();
        cancellation.cancel();
        cancellation.onCancel(called::incrementAndGet);
        assertEquals(2, called.get());
        assertThrows(java.util.concurrent.CancellationException.class, cancellation::throwIfCancelled);
        ChatCancellation.NONE.cancel();
        assertFalse(ChatCancellation.NONE.isCancelled());
    }

    @Test
    void shouldNotMissRegistrationRacingWithCancellation() throws Exception {
        var workers = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 100; round++) {
                ChatCancellation cancellation = new ChatCancellation();
                AtomicInteger called = new AtomicInteger();
                var registration = workers.submit(() -> cancellation.onCancel(called::incrementAndGet));
                var cancelled = workers.submit(cancellation::cancel);
                registration.get(2, TimeUnit.SECONDS);
                cancelled.get(2, TimeUnit.SECONDS);
                assertEquals(1, called.get());
            }
        } finally {
            workers.shutdownNow();
        }
    }
}
