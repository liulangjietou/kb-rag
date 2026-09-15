package io.kbrag.api.sse;

import org.junit.jupiter.api.Test;

import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SseChatStreamFactoryTest {

    @Test
    void shouldCloseAllActiveStreamsAndRefuseNewWorkAfterShutdown() {
        var factory = new SseChatStreamFactory();
        try {
            var first = factory.create();
            var second = factory.create();
            factory.close();
            assertTrue(first.cancellation().isCancelled());
            assertTrue(second.cancellation().isCancelled());
            assertThrows(RejectedExecutionException.class, factory::create);
        } finally {
            factory.close();
        }
    }
}
