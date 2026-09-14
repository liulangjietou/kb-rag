package io.kbrag.infrastructure.provider.chat;

import io.kbrag.common.exception.ProviderException;
import io.kbrag.domain.model.ModelTokenUsage;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/** 已收到截断原因后仍读取计量帧，但不能继续输出或在 DONE 时转为成功。 */
class ChatStreamTerminationTest {
    @Test
    void shouldRejectTruncationAfterTrailingUsageAndKeepOnlyTheOriginalPartialContent() {
        List<String> deltas = new ArrayList<>();
        var subscriber = new OpenAiChatStreamSubscriber(200, deltas::add);
        subscriber.onSubscribe(mock(Flow.Subscription.class));
        feed(subscriber, "data: {\"choices\":[{\"delta\":{\"content\":\"已保存片段\"},\"finish_reason\":\"length\"}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"异常迟到正文\"},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2,\"total_tokens\":12}}\n\n"
                + "data: [DONE]\n\n");

        ProviderException failure = assertInstanceOf(ProviderException.class, assertThrows(CompletionException.class,
                () -> subscriber.getBody().toCompletableFuture().join()).getCause());
        assertEquals("OUTPUT_TRUNCATED", failure.getErrorType().name());
        assertEquals(List.of("已保存片段"), deltas);
        assertEquals(new ModelTokenUsage(10L, 2L, 12L, true), subscriber.usage());
    }

    @Test
    void shouldRetainTheKnownTruncationReasonWhenTheTerminalFrameIsMissing() {
        var subscriber = new OpenAiChatStreamSubscriber(200, ignored -> { });
        subscriber.onSubscribe(mock(Flow.Subscription.class));
        feed(subscriber, "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}\n\n");
        subscriber.onComplete();

        ProviderException failure = assertInstanceOf(ProviderException.class, assertThrows(CompletionException.class,
                () -> subscriber.getBody().toCompletableFuture().join()).getCause());
        assertEquals("OUTPUT_TRUNCATED", failure.getErrorType().name());
        assertEquals(ModelTokenUsage.unknown(), subscriber.usage());
    }

    private void feed(OpenAiChatStreamSubscriber subscriber, String response) {
        subscriber.onNext(List.of(ByteBuffer.wrap(response.getBytes(StandardCharsets.UTF_8))));
    }
}
