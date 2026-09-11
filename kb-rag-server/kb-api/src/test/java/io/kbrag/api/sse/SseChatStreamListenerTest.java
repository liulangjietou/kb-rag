package io.kbrag.api.sse;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** 不同传输结束信号都取消同一请求，竞态下仍只写一个终态。 */
class SseChatStreamListenerTest {

    @Test
    void shouldCancelOnWriteFailureAndSuppressFurtherEvents() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        doThrow(new IOException("disconnected")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        var listener = new SseChatStreamListener(emitter);
        AtomicInteger cancelled = new AtomicInteger();
        listener.cancellation().onCancel(cancelled::incrementAndGet);
        listener.onDelta("部分");
        listener.onDone("request", List.of(), List.of());
        listener.onError("error", "失败");
        listener.heartbeat();
        assertTrue(listener.cancellation().isCancelled());
        assertEquals(1, cancelled.get());
        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
    }

    @Test
    void shouldCancelOnEveryServletLifecycleCallback() {
        SseEmitter emitter = mock(SseEmitter.class);
        var listener = new SseChatStreamListener(emitter);
        ArgumentCaptor<Runnable> completed = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Runnable> timeout = ArgumentCaptor.forClass(Runnable.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<Throwable>> error = ArgumentCaptor.forClass(Consumer.class);
        verify(emitter).onCompletion(completed.capture());
        verify(emitter).onTimeout(timeout.capture());
        verify(emitter).onError(error.capture());
        AtomicInteger cancelled = new AtomicInteger();
        listener.cancellation().onCancel(cancelled::incrementAndGet);
        error.getValue().accept(new IOException("gone"));
        timeout.getValue().run();
        completed.getValue().run();
        assertTrue(listener.cancellation().isCancelled());
        assertEquals(1, cancelled.get());
        verify(emitter).complete();
    }

    @Test
    void shouldWriteExactlyOneTerminalWhenCompletionAndFailureRace() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        var listener = new SseChatStreamListener(emitter);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var success = workers.submit(() -> listener.onDone("request", List.of(), List.of()));
            var failure = workers.submit(() -> listener.onError("error", "失败"));
            success.get(2, TimeUnit.SECONDS);
            failure.get(2, TimeUnit.SECONDS);
            verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
            verify(emitter, times(1)).complete();
        } finally {
            workers.shutdownNow();
        }
    }
}
