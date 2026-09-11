package io.kbrag.infrastructure.provider.chat;

import io.kbrag.common.exception.ProviderException;
import io.kbrag.domain.model.ModelTokenUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** 覆盖真实网络分片会触发的 UTF-8、换行、终止帧和数据量边界。 */
class OpenAiChatStreamSubscriberTest {

    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r", "\r\n"})
    void shouldDecodeEveryByteBoundaryAndKeepFinalUsage(String separator) {
        List<String> deltas = new ArrayList<>();
        var subscriber = new OpenAiChatStreamSubscriber(200, deltas::add);
        Flow.Subscription subscription = mock(Flow.Subscription.class);
        subscriber.onSubscribe(subscription);
        String response = "\uFEFF: keep-alive" + separator + separator
                + "data: {\"choices\":[{\"delta\":{\"content\":\"你好😀\"}}]}" + separator + separator
                + "data: {\"choices\":[]," + separator
                + "data: \"usage\":{\"prompt_tokens\":10,\"completion_tokens\":3,\"total_tokens\":13}}"
                + separator + separator + "data: [DONE]" + separator + separator;
        for (byte value : response.getBytes(StandardCharsets.UTF_8)) {
            subscriber.onNext(List.of(ByteBuffer.wrap(new byte[]{value})));
        }
        assertEquals(List.of("你好😀"), deltas);
        assertEquals(new ModelTokenUsage(10L, 3L, 13L, true), subscriber.getBody().toCompletableFuture().join());
        verify(subscription, atLeastOnce()).cancel();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "data: [DONE]", "data: [DONE]\n", "data: {broken}\n\n",
            "data: {\"error\":{\"message\":\"private\"}}\n\n"})
    void shouldRejectTruncatedOrMalformedStreams(String tail) {
        List<String> deltas = new ArrayList<>();
        var subscriber = new OpenAiChatStreamSubscriber(200, deltas::add);
        subscriber.onSubscribe(mock(Flow.Subscription.class));
        feed(subscriber, "data: {\"choices\":[{\"delta\":{\"content\":\"保留片段\"}}]}\n\n" + tail);
        subscriber.onComplete();
        assertInstanceOf(ProviderException.class, assertThrows(CompletionException.class,
                () -> subscriber.getBody().toCompletableFuture().join()).getCause());
        assertEquals(List.of("保留片段"), deltas);
    }

    @Test
    void shouldRejectAnUnboundedLineAndStopTheSubscription() {
        var subscriber = new OpenAiChatStreamSubscriber(200, delta -> { });
        Flow.Subscription subscription = mock(Flow.Subscription.class);
        subscriber.onSubscribe(subscription);
        feed(subscriber, "data:" + "x".repeat(65_536));
        assertThrows(CompletionException.class, () -> subscriber.getBody().toCompletableFuture().join());
        verify(subscription).cancel();
    }

    @Test
    void shouldIgnoreAnythingAfterTheTerminalFrame() {
        List<String> deltas = new ArrayList<>();
        var subscriber = new OpenAiChatStreamSubscriber(200, deltas::add);
        subscriber.onSubscribe(mock(Flow.Subscription.class));
        feed(subscriber, "data: [DONE]\n\ndata: {malformed}\n\n");
        subscriber.onComplete();
        assertEquals(ModelTokenUsage.unknown(), subscriber.getBody().toCompletableFuture().join());
        assertEquals(List.of(), deltas);
    }

    private void feed(OpenAiChatStreamSubscriber subscriber, String response) {
        subscriber.onNext(List.of(ByteBuffer.wrap(response.getBytes(StandardCharsets.UTF_8))));
    }
}
