package io.kbrag.api.sse;

import io.kbrag.api.dto.KnowledgeChatResponse;
import io.kbrag.app.openapi.ChatDiagnostics;
import io.kbrag.app.openapi.KnowledgeCallResult;
import io.kbrag.app.retrieval.RerankTiming;
import io.kbrag.common.util.JsonUtil;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;

/** 使用真实 SSE 构造器和 JSON 投影核对扩展事件，旧业务终态保持原行为。 */
class SseChatDiagnosticsTest {

    private final ChatDiagnostics diagnostics = new ChatDiagnostics(ChatDiagnostics.Outcome.FAILED,
            ChatDiagnostics.Stage.RETRIEVAL, 0L, 25L, null, null, 25L,
            new RerankTiming(RerankTiming.Status.TIMEOUT, 12L));

    @Test
    void shouldSendOptionalDiagnosticWithoutClosingAndIgnoreItAfterTerminal() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        SseChatStreamListener listener = new SseChatStreamListener(emitter);
        listener.onDiagnostics(diagnostics);
        verify(emitter, never()).complete();
        listener.onError("INTERNAL_ERROR", "检索失败");
        listener.onDiagnostics(diagnostics);

        ArgumentCaptor<SseEmitter.SseEventBuilder> events = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, times(2)).send(events.capture());
        String wire = events.getAllValues().get(0).build().stream()
                .map(part -> part.getData() instanceof String text ? text : JsonUtil.toJson(part.getData()))
                .reduce("", String::concat);
        assertTrue(wire.contains("event:diagnostics"));
        assertTrue(wire.contains("\"retrieval_ms\":25"));
        assertTrue(wire.contains("\"configuration_ms\":0"));
        assertTrue(wire.contains("\"rerank_status\":\"TIMEOUT\""));
        assertTrue(wire.contains("\"rerank_ms\":12"));
        assertFalse(wire.contains("\"generation_ms\":0"));
        verify(emitter).complete();
    }

    @Test
    void shouldOmitDiagnosticsForUninstrumentedCallsAndUseSameShapeForPreviewJson() {
        KnowledgeCallResult base = KnowledgeCallResult.builder().answer("回答").nodes(List.of()).degraded(List.of()).build();
        assertFalse(JsonUtil.toJson(KnowledgeChatResponse.from(base)).contains("\"diagnostics\""));
        String preview = JsonUtil.toJson(KnowledgeChatResponse.from(base.toBuilder().diagnostics(diagnostics).build()));
        assertTrue(preview.contains("\"diagnostics\""));
        assertTrue(preview.contains("\"failed_stage\":\"RETRIEVAL\""));
        assertTrue(preview.contains("\"total_ms\":25"));
        assertTrue(preview.contains("\"rerank_status\":\"TIMEOUT\""));
        assertTrue(preview.contains("\"rerank_ms\":12"));
    }
}
